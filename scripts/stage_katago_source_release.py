#!/usr/bin/env python3
"""Seal all 15 audited source targets for a reviewed release, without uploading anything."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import tempfile
import zipfile

from build_katago_source import SOURCE_COMMIT, TARGETS, check_release_receipts, check_source
from katago_asset_catalog import load_catalog, validate_catalog


def digest(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def normalized_name(value: object) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError("missing relative file name")
    value = value.replace("\\", "/")
    path = PurePosixPath(value)
    if (path.is_absolute() or str(path) != value or ".." in path.parts
            or any(":" in part or part.endswith((".", " ")) for part in path.parts)):
        raise ValueError("unsafe relative file name")
    return value


def record(path: Path, name: str) -> dict:
    return {"file": name, "sizeBytes": path.stat().st_size, "sha256": digest(path)}


def verified_package(root: Path, target: str) -> tuple[dict, dict[str, Path], str]:
    if root.is_symlink():
        raise ValueError("package directory cannot be a symlink")
    receipt_name = "source-build.json" if target.startswith("macos-") else "source-package.json"
    receipt_file = root / receipt_name
    receipt = json.loads(receipt_file.read_text(encoding="utf-8"))
    if (receipt.get("target") != target or receipt.get("sourceCommit") != SOURCE_COMMIT
            or receipt.get("sourceRepository") != "https://github.com/lightvector/KataGo"
            or receipt.get("origin") != "project-source-build"
            or receipt.get("backend") != TARGETS[target][2]):
        raise ValueError(f"{target}: incorrect source package identity")
    for key in ("buildStatus", "packagingStatus", "dependencyAuditStatus"):
        if receipt.get(key) != "PASS":
            raise ValueError(f"{target}: {key} did not pass")
    if receipt.get("hardwareAcceptanceStatus") == "FAIL" or receipt.get("error"):
        raise ValueError(f"{target}: failed evidence cannot be promoted")
    if (not isinstance(receipt.get("compiler"), dict) or not receipt["compiler"]
            or not re.fullmatch(r"[0-9a-f]{64}", str(receipt.get("dependencyLockSha256", "")))
            or f"Git revision: {SOURCE_COMMIT}" not in receipt.get("versionOutput", "")):
        raise ValueError(f"{target}: compiler, dependency lock or version evidence missing")
    listed = {}
    folded = set()
    for item in receipt.get("files", []):
        name = normalized_name(item.get("file"))
        if name.casefold() in folded or name == receipt_name:
            raise ValueError(f"{target}: duplicate or recursive inventory entry")
        folded.add(name.casefold())
        path = root.joinpath(*PurePosixPath(name).parts)
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"{target}: missing file or symlink: {name}")
        expected = dict(item, file=name)
        if type(expected.get("sizeBytes")) is not int or record(path, name) != expected:
            raise ValueError(f"{target}: modified package file: {name}")
        listed[name] = path
    actual = set()
    for path in root.rglob("*"):
        if path.is_symlink():
            raise ValueError(f"{target}: package contains a symlink")
        if path.is_file() and path != receipt_file:
            actual.add(path.relative_to(root).as_posix())
    if actual != set(listed):
        raise ValueError(f"{target}: inventory does not cover the exact package")
    executable = "katago.exe" if target.startswith("windows-") else "katago"
    if executable not in listed or record(listed[executable], executable) != receipt.get("executable"):
        raise ValueError(f"{target}: executable identity mismatch")
    return receipt, listed, receipt_name


def acceptance_record(path: Path, receipt: dict, label: str) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if (data.get("sourceCommit") != SOURCE_COMMIT
            or data.get("executableSha256") != receipt["executable"]["sha256"]):
        raise ValueError(f"{receipt['target']}: {label} evidence belongs to a different executable")
    return data


def accept(root: Path, receipt: dict) -> dict:
    accepted = copy.deepcopy(receipt)
    hardware = acceptance_record(root / "hardware.json", receipt, "hardware")
    status = hardware.get("status")
    if status == "PASS":
        if (receipt["target"] in {"windows-directml", "windows-openvino"}
                and hardware.get("onnxProvider") == "cpu"):
            raise ValueError("ONNX CPU evidence cannot certify GPU/NPU hardware")
        # This is the existing real-process focus probe contract, not a handwritten PASS flag.
        steps = hardware.get("steps", [])
        commands = [step.get("command", "") for step in steps]
        if (len(steps) != 5 or any(type(step.get("rootVisits")) is not int
                                 or step["rootVisits"] <= 0 for step in steps)
                or "focus Q4 0.5" not in commands[1]
                or "focus Q4,D16 0.5" not in commands[2]
                or "focus D16 0.5" not in commands[3]
                or "focus " in commands[0] or "focus " in commands[4]):
            raise ValueError(f"{receipt['target']}: incomplete real inference evidence")
    elif status == "PENDING_HARDWARE":
        if TARGETS[receipt["target"]][2] == "EIGEN" or not str(hardware.get("reason", "")).strip():
            raise ValueError("CPU execution cannot be deferred; missing GPUs require a reason")
        accepted["hardwareAcceptanceReason"] = hardware["reason"]
    else:
        raise ValueError(f"{receipt['target']}: hardware evidence failed or is missing")
    accepted["hardwareAcceptanceStatus"] = status
    accepted["acceptanceEvidence"] = {"hardware": record(root / "hardware.json", "hardware.json")}
    if receipt["target"].startswith("linux-"):
        abi = acceptance_record(root / "linux-compatibility.json", receipt, "Linux ABI")
        if (abi.get("status") != "PASS" or not abi.get("baselineExecutableSha256")
                or not abi.get("symbolCeilings") or not abi.get("distributionChecks")
                or any(check.get("status") != "PASS" for check in abi["distributionChecks"])):
            raise ValueError(f"{receipt['target']}: Linux ABI and distribution verification missing")
        accepted["productionAbiAcceptanceStatus"] = "PASS"
        accepted["acceptanceEvidence"]["linuxCompatibility"] = record(
            root / "linux-compatibility.json", "linux-compatibility.json")
    return accepted


def archive_files(target: str, files: dict[str, Path]) -> dict[str, Path]:
    # CUDA/TRT runtime is already separately hash-locked and installed by the app.
    # Keep their repair archives small instead of duplicating gigabytes of vendor DLLs.
    if target in {"windows-nvidia", "windows-tensorrt"}:
        return {name: path for name, path in files.items()
                if name == "katago.exe" or name.startswith("licenses/")}
    return files


def write_zip(path: Path, files: dict[str, Path], metadata: dict) -> None:
    with zipfile.ZipFile(path, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, source in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            mode = 0o755 if name == "katago" else 0o644
            info.external_attr = (stat.S_IFREG | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            with source.open("rb") as src, archive.open(info, "w", force_zip64=True) as dest:
                shutil.copyfileobj(src, dest, 1024 * 1024)
        info = zipfile.ZipInfo("source-release.json", (1980, 1, 1, 0, 0, 0))
        info.external_attr = (stat.S_IFREG | 0o644) << 16
        archive.writestr(info, json.dumps(metadata, indent=2, sort_keys=True) + "\n")


def verify_archive(path: Path, target: str) -> dict:
    with zipfile.ZipFile(path) as archive:
        names = [normalized_name(item.filename) for item in archive.infolist()]
        if len({name.casefold() for name in names}) != len(names):
            raise ValueError("source archive contains duplicate names")
        metadata = json.loads(archive.read("source-release.json"))
        if metadata.get("target") != target or metadata.get("sourceCommit") != SOURCE_COMMIT:
            raise ValueError("source archive belongs to a different source or target")
        expected = {}
        for item in metadata.get("files", []):
            name = normalized_name(item.get("file"))
            if name.casefold() in {value.casefold() for value in expected}:
                raise ValueError("source archive inventory contains duplicate names")
            expected[name] = item
        if set(names) != set(expected) | {"source-release.json"}:
            raise ValueError("source archive inventory is not complete")
        for name, item in expected.items():
            info = archive.getinfo(name)
            if (info.file_size != item.get("sizeBytes")
                    or stat.S_IFMT(info.external_attr >> 16) != stat.S_IFREG):
                raise ValueError("source archive contains a wrong length or non-regular file")
            with archive.open(info) as source:
                if hashlib.file_digest(source, "sha256").hexdigest() != item.get("sha256"):
                    raise ValueError("source archive file checksum mismatch")
        return metadata


def stage(packages: Path, acceptance: Path, base_catalog: Path, output: Path, tag: str,
          source: Path) -> dict:
    if output.exists():
        raise ValueError("release staging output must be new; never overwrite a published artifact")
    catalog = copy.deepcopy(load_catalog(base_catalog))
    check_source(source)
    templates = {"default_gtp.cfg": source / "cpp/configs/gtp_example.cfg",
                 "analysis_example.cfg": source / "cpp/configs/analysis_example.cfg"}
    if not all(path.is_file() and not path.is_symlink() for path in templates.values()):
        raise ValueError("pinned source configuration templates missing")
    catalog.update(origin="project-source-build", katagoVersion="1.18.2", katagoReleaseTag="v1.18.2",
                   katagoSourceCommit=SOURCE_COMMIT, engineReleaseRepository="wimi321/lizzieyzy-next",
                   engineReleaseTag=tag)
    verified = {}
    accepted = []
    for target in TARGETS:
        receipt, files, receipt_name = verified_package(packages / target, target)
        accepted.append(accept(acceptance / target, receipt))
        verified[target] = (receipt, files, receipt_name)
    check_release_receipts(accepted)
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".katago-source-stage-", dir=output.parent) as temporary:
        staging = Path(temporary)
        catalog["assets"] = {}
        baseline_assets = load_catalog(base_catalog)["assets"]
        for result in accepted:
            target = result["target"]
            receipt, files, receipt_name = verified[target]
            selected = archive_files(target, files)
            selected = dict(selected, **{receipt_name: packages / target / receipt_name})
            if set(selected) & set(templates):
                raise ValueError("source config templates would overwrite a packaged file")
            selected.update(templates)
            metadata = dict(result, files=[record(path, name) for name, path in sorted(selected.items())],
                            runtimeBundled=target not in {"windows-nvidia", "windows-tensorrt"})
            asset_name = f"katago-source-{SOURCE_COMMIT[:12]}-{target}.zip"
            path = staging / asset_name
            write_zip(path, selected, metadata)
            verify_archive(path, target)
            if path.stat().st_size >= 2_147_483_648:
                raise ValueError(f"{target}: engine archive exceeds the GitHub asset limit")
            base = baseline_assets.get(target, {"platform": target, "backend": "metal", "releaseTier": "stable"})
            catalog["assets"][target] = dict(
                base, assetName=asset_name, sha256=digest(path), sizeBytes=path.stat().st_size,
                executableSha256=receipt["executable"]["sha256"])
        validate_catalog(catalog)
        check_source(source)
        (staging / "katago-assets.json").write_text(json.dumps(catalog, indent=2) + "\n", encoding="utf-8")
        (staging / "source-release-evidence.json").write_text(
            json.dumps({"sourceCommit": SOURCE_COMMIT, "targets": accepted}, indent=2) + "\n", encoding="utf-8")
        # No partial catalog is visible if any source, hash, platform or acceptance gate fails.
        staging.rename(output)
    return catalog


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packages", required=True, type=Path)
    parser.add_argument("--acceptance", required=True, type=Path)
    parser.add_argument("--base-catalog", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--source", required=True, type=Path)
    args = parser.parse_args()
    stage(args.packages, args.acceptance, args.base_catalog, args.output, args.tag, args.source)
