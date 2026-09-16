#!/usr/bin/env python3
"""Package a verified source build; do not grant hardware or release approval."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess

from build_katago_source import SOURCE_COMMIT, TARGETS, file_record
from build_katago_macos_dependencies import verify_sdk
from macos_katago_bundle import audit_bundle, build_bundle


def validate_build(build: Path, sdk: Path) -> dict:
    receipt = json.loads((build / "source-build.json").read_text(encoding="utf-8"))
    target = receipt.get("target")
    if (target not in {"macos-arm64", "macos-amd64"} or receipt.get("buildStatus") != "PASS"
            or receipt.get("sourceCommit") != SOURCE_COMMIT or receipt.get("backend") != "METAL"
            or receipt.get("origin") != "project-source-build"):
        raise ValueError("Not a verified pinned macOS source build")
    sdk_receipt = verify_sdk(sdk, TARGETS[target][1])
    if (receipt.get("dependencyLockSha256") != sdk_receipt["lockSha256"]
            or receipt.get("sdkReceipt") != file_record(sdk / "sdk-receipt.json", sdk)):
        raise ValueError("Source build used a different SDK")
    if receipt.get("executable") != file_record(build / "katago", build):
        raise ValueError("KataGo executable changed after compilation")
    return receipt


def package(build: Path, sdk: Path, output: Path) -> dict:
    build, sdk, output = build.resolve(), sdk.resolve(), output.resolve()
    if output.exists() or output == build or build in output.parents or sdk in output.parents:
        raise ValueError("Package output must be a fresh directory outside the build and SDK")
    receipt = validate_build(build, sdk)
    build_bundle(build / "katago", output, None)
    shutil.copytree(sdk / "share/licenses", output / "licenses")
    shutil.copytree(build / "source-licenses", output / "licenses/KataGo")
    shutil.copy2(sdk / "sdk-receipt.json", output / "sdk-receipt.json")
    version = subprocess.check_output([str(output / "katago"), "version"], text=True, timeout=90)
    if f"Git revision: {SOURCE_COMMIT}" not in version or "Using Metal backend" not in version:
        raise ValueError("Packaged executable lost pinned source identity")
    arches = subprocess.check_output(["lipo", "-archs", str(output / "katago")], text=True).split()
    if arches != [TARGETS[receipt["target"]][1]]:
        raise ValueError("Packaged executable has the wrong architecture")
    audit_bundle(output, None)
    receipt.update(
        packagingStatus="PASS", dependencyAuditStatus="PASS",
        hardwareAcceptanceStatus="NOT_RUN", executable=file_record(output / "katago", output),
        compiledExecutable=receipt["executable"],
        files=[file_record(path, output) for path in sorted(output.rglob("*")) if path.is_file()],
    )
    (output / "source-build.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    package(args.build, args.sdk, args.output)


if __name__ == "__main__":
    main()
