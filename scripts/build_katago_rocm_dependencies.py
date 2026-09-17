#!/usr/bin/env python3
"""Seal ROCm 7.13 headers/compiler and unchanged per-family Windows runtimes."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import zipfile

import build_katago_windows_dependencies as common
from build_katago_directml_dependencies import copy_member
from build_katago_macos_dependencies import digest, extract_verified, inventory

LOCK_PATH = Path(__file__).with_name("katago_rocm_dependencies.json")
TARGETS = {"windows-rocm-gfx103x", "windows-rocm-gfx110x",
           "windows-rocm-gfx1151", "windows-rocm-gfx120x"}
RUNTIME_DIRECTORIES = ("rocblas", "hipblaslt")


def runtime_files() -> list[str]:
    return json.loads(LOCK_PATH.read_text(encoding="utf-8"))["runtimeFiles"]


def engine_options(prefix: Path) -> list[str]:
    prefix = prefix.resolve()
    hip = prefix / "rocm"
    architectures = json.loads(LOCK_PATH.read_text(encoding="utf-8"))["hipArchitectures"]
    options = [option for option in common.engine_options(prefix, "windows-cpu")
               if not option.startswith(("-DEIGEN3_INCLUDE_DIRS=", "-DCMAKE_PREFIX_PATH=", "-DCMAKE_FIND_ROOT_PATH="))]
    return options + [f"-DCMAKE_PREFIX_PATH={hip};{prefix}", f"-DCMAKE_FIND_ROOT_PATH={hip};{prefix}",
                      f"-DCMAKE_HIP_COMPILER_ROCM_ROOT={hip}",
                      f"-DCMAKE_HIP_COMPILER={hip / 'lib/llvm/bin/clang++.exe'}",
                      f"-DCMAKE_CXX_COMPILER={hip / 'lib/llvm/bin/clang++.exe'}",
                      f"-DCMAKE_C_COMPILER={hip / 'lib/llvm/bin/clang.exe'}",
                      "-DCMAKE_HIP_ARCHITECTURES=" + ";".join(architectures)]


def environment(prefix: Path) -> dict[str, str]:
    env = common.environment(prefix)
    hip = prefix.resolve() / "rocm"
    env["HIP_PATH"] = hip.as_posix()
    env["ROCM_PATH"] = hip.as_posix()
    env["PATH"] = os.pathsep.join((str(prefix / "runtime"), str(hip / "bin"),
                                 str(hip / "lib/llvm/bin"), env.get("PATH", "")))
    return env


def verify_archive(archive: Path, item: dict) -> None:
    if archive.stat().st_size != item["sizeBytes"] or digest(archive) != item["sha256"]:
        raise ValueError("ROCm archive size or SHA-256 mismatch")


def runtime_entries(bundle: zipfile.ZipFile, required: set[str]) -> list[tuple[str, PurePosixPath]]:
    seen, dlls, directories, entries = set(), set(), set(), []
    for info in bundle.infolist():
        name = info.filename.rstrip("/")
        path = PurePosixPath(name)
        if (not name or path.is_absolute() or "\\" in name
                or any(part in {"", ".", ".."} or ":" in part or part.endswith((" ", "."))
                       for part in name.split("/"))
                or (info.external_attr >> 16) & 0o170000 == 0o120000
                or name.casefold() in seen):
            raise ValueError("Unsafe or duplicate ROCm archive entry")
        seen.add(name.casefold())
        if info.is_dir():
            continue
        if len(path.parts) == 1 and path.suffix.lower() == ".dll":
            dlls.add(name)
            entries.append((info.filename, path))
        elif path.parts[0] in RUNTIME_DIRECTORIES:
            if len(path.parts) < 3 or path.parts[1] != "library" or path.suffix.lower() == ".exe":
                raise ValueError("Unexpected ROCm kernel library path")
            directories.add(path.parts[0])
            entries.append((info.filename, path))
    if dlls != required or directories != set(RUNTIME_DIRECTORIES):
        raise ValueError("ROCm runtime or kernel library inventory differs from lock")
    return entries


def install_runtime(archive: Path, prefix: Path, lock: dict, target: str) -> None:
    verify_archive(archive, lock["runtimes"][target])
    with zipfile.ZipFile(archive) as bundle:
        entries = runtime_entries(bundle, set(lock["runtimeFiles"]))
        if any((prefix / "runtime" / path).exists() for _, path in entries):
            raise ValueError("ROCm runtime would overwrite an SDK file")
        for member, relative in entries:
            copy_member(bundle, member, prefix / "runtime" / relative)
        # Keep the original runtime guidance, never the old upstream executable.
        copy_member(bundle, "README_ROCM.txt", prefix / "share/licenses/rocm/README_ROCM.txt")


def validate_build(build: Path) -> None:
    log = (build / "build.log").read_text(encoding="utf-8", errors="replace")
    toolset = json.loads(common.LOCK_PATH.read_text(encoding="utf-8"))["msvcToolset"]
    chosen = re.findall(r"MSVC toolset ([\d.]+) is compatible with the HIP compiler; using it", log)
    if len(chosen) != 1 or not chosen[0].startswith(toolset + "."):
        raise ValueError("ROCm selected a different or unverified MSVC toolset")
    cache = (build / "CMakeCache.txt").read_text(encoding="utf-8")
    archs = ";".join(json.loads(LOCK_PATH.read_text(encoding="utf-8"))["hipArchitectures"])
    if not re.search(r"(?m)^CMAKE_HIP_ARCHITECTURES:[^=]+=" + re.escape(archs) + r"$", cache):
        raise ValueError("ROCm GPU architecture range changed during configure")


def verify_sdk(prefix: Path) -> dict:
    prefix = prefix.resolve()
    receipt = json.loads((prefix / "sdk-receipt.json").read_text(encoding="utf-8"))
    if (receipt.get("schemaVersion") != 1 or receipt.get("status") != "PASS"
            or receipt.get("provider") != "rocm" or receipt.get("system") != "Windows"
            or receipt.get("arch") != "x86_64" or receipt.get("target") not in TARGETS
            or receipt.get("lockSha256") != digest(LOCK_PATH)
            or receipt.get("commonLockSha256") != digest(common.LOCK_PATH)
            or receipt.get("configuration") != engine_options(prefix)
            or not receipt.get("files") or receipt["files"] != inventory(prefix)):
        raise ValueError("ROCm SDK receipt or files do not match the locked build")
    return receipt


def download(item: dict, archive: Path, log) -> None:
    subprocess.run(["curl.exe", "--fail", "--location", "--proto", "=https", "--proto-redir", "=https",
                    "--retry", "3", "--connect-timeout", "30", "--max-time", "1800", "--output",
                    str(archive), item["url"]], check=True, stdout=log, stderr=subprocess.STDOUT)
    verify_archive(archive, item)


def build_sdk(output: Path, target: str, jobs: int) -> Path:
    if target not in TARGETS:
        raise ValueError("Unknown ROCm GPU family")
    output = output.resolve()
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    prefix = common.build_sdk(output, jobs)
    base = common.verify_sdk(prefix)
    receipt = dict(schemaVersion=1, status="FAIL", provider="rocm", system="Windows", arch="x86_64",
                   target=target, lockSha256=digest(LOCK_PATH), commonLockSha256=digest(common.LOCK_PATH),
                   commonSdk=base, configuration=engine_options(prefix))
    (prefix / "sdk-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    try:
        if shutil.disk_usage(output).free < 30_000_000_000:
            raise ValueError("ROCm source build needs at least 30 GB free temporary disk space")
        with (output / "build.log").open("a", encoding="utf-8") as log:
            archive = output / "rocm-sdk.tar.gz"
            download(lock["sdk"], archive, log)
            hip = extract_verified(archive, output / "rocm-source", lock["sdk"]["sha256"])
            if hip.name != lock["sdk"]["archiveRoot"] or (hip / ".info/version").read_text().strip() != lock["version"]:
                raise ValueError("ROCm SDK root or embedded version differs from lock")
            shutil.move(hip, prefix / "rocm")
            hip = prefix / "rocm"
            for name in ("lib/llvm/bin/clang++.exe", "lib/llvm/bin/clang.exe", "lib/MIOpen.lib",
                         "lib/hipblas.lib", "include/hip/hip_runtime.h"):
                if not (hip / name).is_file():
                    raise ValueError(f"ROCm SDK component missing: {name}")
            for path in hip.rglob("*"):
                if path.is_file() and path.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")):
                    destination = prefix / "share/licenses/rocm" / path.relative_to(hip)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, destination)
            if not (prefix / "share/licenses/rocm/share/hip/LICENSE.md").is_file():
                raise ValueError("ROCm redistribution notices are missing")
            runtime = output / "runtime-baseline.zip"
            download(lock["runtimes"][target], runtime, log)
            install_runtime(runtime, prefix, lock, target)
        for item in base["files"]:
            path = prefix / item["file"]
            if not path.is_file() or path.stat().st_size != item["sizeBytes"] or digest(path) != item["sha256"]:
                raise ValueError(f"Common SDK changed during ROCm installation: {item['file']}")
        receipt.update(status="PASS", files=inventory(prefix),
                       dependencies=base["dependencies"] + [lock["sdk"], lock["runtimes"][target]])
    except Exception as error:
        receipt["error"] = str(error)
        raise
    finally:
        (prefix / "sdk-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return prefix


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--target", required=True, choices=sorted(TARGETS))
    parser.add_argument("--jobs", type=int, default=3)
    args = parser.parse_args()
    print(build_sdk(args.output, args.target, args.jobs))
