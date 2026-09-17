#!/usr/bin/env python3
"""Compare pinned production ABI and run clean Linux distribution loader/inference checks."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess
import zipfile

from build_katago_source import SOURCE_COMMIT
from stage_katago_source_release import digest, normalized_name, verified_package
import build_katago_linux_cuda_dependencies as cuda

LOCK_PATH = Path(__file__).with_name("katago_linux_compatibility.json")
NAMESPACES = ("GLIBC", "GLIBCXX", "CXXABI")


def symbol_versions(text: str) -> dict[str, tuple[int, ...]]:
    return {name: max((tuple(map(int, value.split("."))) for value in
                      re.findall(r"\b" + name + r"_([0-9]+(?:\.[0-9]+)+)\b", text)), default=())
            for name in NAMESPACES}


def compare_symbols(baseline: str, candidates: dict[str, str]) -> dict:
    ceilings = symbol_versions(baseline)
    if not all(ceilings.values()):
        raise ValueError("baseline must contain all three ABI symbol namespaces")
    result = {}
    for path, text in candidates.items():
        actual = symbol_versions(text)
        for namespace in NAMESPACES:
            if actual[namespace] > ceilings[namespace]:
                raise ValueError(f"{path} raises {namespace} beyond the previous production engine")
        result[path] = {key: ".".join(map(str, value)) for key, value in actual.items()}
    if not result:
        raise ValueError("no candidate ELF binaries were inspected")
    return {"baseline": {key: ".".join(map(str, value)) for key, value in ceilings.items()},
            "candidates": result}


def extract_baseline(archive: Path, output: Path, expected: dict) -> None:
    if archive.stat().st_size != expected["sizeBytes"] or digest(archive) != expected["sha256"]:
        raise ValueError("official baseline archive size or SHA-256 mismatch")
    with zipfile.ZipFile(archive) as opened:
        names = [normalized_name(item.filename) for item in opened.infolist() if not item.is_dir()]
        if len(set(name.casefold() for name in names)) != len(names) or "katago" not in names:
            raise ValueError("ambiguous baseline archive")
        # Only the trusted AppImage executable is needed, not an arbitrary extraction tree.
        output.mkdir(parents=True, exist_ok=False)
        with opened.open("katago") as source, (output / "katago").open("xb") as destination:
            shutil.copyfileobj(source, destination)
    (output / "katago").chmod(0o755)


def checked(command: list[str], log, **kwargs) -> str:
    log.write("$ " + " ".join(command) + "\n")
    log.flush()
    result = subprocess.run(command, capture_output=True, text=True, timeout=300, **kwargs)
    log.write(result.stdout + result.stderr + "\n")
    log.flush()
    result.check_returncode()
    return result.stdout


def verify_version(text: str, source: bool) -> None:
    if source:
        if f"Git revision: {SOURCE_COMMIT}" not in text or "KataGo v1.18.2" not in text:
            raise ValueError("distribution ran a different source engine")
    elif "KataGo v1.18.1" not in text:
        raise ValueError("distribution ran a different baseline engine")


def audit(package: Path, sdk: Path, model: Path, output: Path) -> dict:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("distribution acceptance requires native Linux x86_64")
    package, sdk, model, output = [path.resolve() for path in (package, sdk, model, output)]
    receipt = json.loads((package / "source-package.json").read_text(encoding="utf-8"))
    target = receipt.get("target")
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if target not in lock["baselines"]:
        raise ValueError("unsupported Linux compatibility target")
    verified_package(package, target)
    if target == "linux-nvidia":
        cuda.verify_sdk(sdk)
    if digest(model) != "f5d32604e3675c480c7c8f6aa579a1ea857135628a0afccc8fa56330fbacd38d":
        raise ValueError("inference model does not match the pinned upstream fixture")
    output.mkdir(parents=True, exist_ok=False)
    result = dict(status="FAIL", sourceCommit=SOURCE_COMMIT, target=target,
                  executableSha256=receipt["executable"]["sha256"],
                  compatibilityLockSha256=digest(LOCK_PATH), distributionChecks=[])
    try:
        with (output / "compatibility.log").open("w", encoding="utf-8") as log:
            expected = lock["baselines"][target]
            archive = output / expected["assetName"]
            checked(["curl", "--fail", "--location", "--proto", "=https", "--proto-redir", "=https",
                     "--retry", "3", "--output", str(archive),
                     f"https://github.com/lightvector/KataGo/releases/download/{lock['baselineTag']}/{archive.name}"], log)
            baseline = output / "baseline"
            extract_baseline(archive, baseline, expected)
            checked([str(baseline / "katago"), "--appimage-extract"], log, cwd=baseline)
            prior = baseline / "squashfs-root/usr/bin/katago"
            result["baselineArchiveSha256"] = digest(archive)
            result["baselineExecutableSha256"] = digest(prior)
            baseline_symbols = checked(["readelf", "--version-info", str(prior)], log)
            candidates = {}
            for path in sorted(package.iterdir()):
                if path.is_file() and (path.name == "katago" or ".so" in path.name):
                    candidates[path.name] = checked(["readelf", "--version-info", str(path)], log)
            result["symbolCeilings"] = compare_symbols(baseline_symbols, candidates)
            config = output / "probe.cfg"
            config.write_text("numSearchThreads = 1\nmaxVisits = 4\nlogToStderr = true\nlogDir = /tmp/katago-logs\n",
                              encoding="utf-8")
            for distribution in lock["distributions"]:
                image = distribution["image"]
                checked(["docker", "pull", "--platform", "linux/amd64", image], log)
                identifier = checked([
                    "docker", "create", "--platform", "linux/amd64",
                    "--mount", f"type=bind,src={package},dst=/engine,readonly",
                    "--mount", f"type=bind,src={baseline},dst=/baseline,readonly",
                    "--mount", f"type=bind,src={sdk},dst=/sdk,readonly",
                    "--mount", f"type=bind,src={model},dst=/probe-model.bin.gz,readonly",
                    "--mount", f"type=bind,src={config},dst=/probe.cfg,readonly",
                    image, "sleep", "1800"], log).strip()
                if not re.fullmatch(r"[0-9a-f]{64}", identifier):
                    raise ValueError("Docker did not return an owned container ID")
                try:
                    checked(["docker", "start", identifier], log)
                    checked(["docker", "exec", identifier, "apt-get", "update"], log)
                    checked(["docker", "exec", identifier, "apt-get", "install", "-y", "--no-install-recommends", "libstdc++6"], log)
                    packages = checked(["docker", "exec", identifier, "dpkg-query", "-W", "libc6", "libstdc++6"], log)
                    external = ":/sdk/cuda/lib:/sdk/cudnn/lib" if target == "linux-nvidia" else ""
                    baseline_env = "LD_LIBRARY_PATH=/baseline/squashfs-root/usr/lib" + external
                    candidate_env = "LD_LIBRARY_PATH=/engine" + external
                    after = checked(["docker", "exec", "-e", candidate_env, identifier, "/engine/katago", "version"], log)
                    verify_version(after, True)
                    check = dict(distribution, status="PASS", installedRuntimePackages=packages,
                                 sourceVersionOutput=after, gpuInferenceStatus="NOT_RUN")
                    if target == "linux-cpu":
                        gtp = checked(["docker", "exec", "-i", "-e", candidate_env, identifier,
                                       "/engine/katago", "gtp", "-config", "/probe.cfg", "-model", "/probe-model.bin.gz"],
                                      log, input="1 boardsize 9\n2 komi 7.5\n3 genmove B\n4 quit\n")
                        if not re.search(r"(?m)^=3 (?:[A-HJ-T][1-9][0-9]?|pass|resign)\s*$", gtp):
                            raise ValueError("distribution CPU inference did not return a legal GTP move")
                        check["cpuInferenceStatus"] = "PASS"
                    # Test the new engine before adding the old bundle's system dependencies.
                    # Otherwise an accidental old SSL/OpenCL dependency could be concealed.
                    baseline_packages = ["openssl"]
                    if target == "linux-opencl":
                        baseline_packages.append("ocl-icd-libopencl1")
                    checked(["docker", "exec", identifier, "apt-get", "install", "-y",
                             "--no-install-recommends", *baseline_packages], log)
                    check["baselineSystemPackages"] = checked(
                        ["docker", "exec", identifier, "dpkg-query", "-W", *baseline_packages], log)
                    before = checked(["docker", "exec", "-e", baseline_env, identifier,
                                      "/baseline/squashfs-root/usr/bin/katago", "version"], log)
                    verify_version(before, False)
                    check["baselineVersionOutput"] = before
                    result["distributionChecks"].append(check)
                finally:
                    checked(["docker", "rm", "--force", identifier], log)
            result["status"] = "PASS"
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        (output / "linux-compatibility.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", required=True, type=Path)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--model", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    audit(args.package, args.sdk, args.model, args.output)
