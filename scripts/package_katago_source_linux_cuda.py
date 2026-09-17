#!/usr/bin/env python3
"""Audit a Linux CUDA engine with explicit, hash-verified external runtime dependencies."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess

from build_katago_source import SOURCE_COMMIT, file_record
from build_katago_linux_cuda_dependencies import LOCK_PATH, TARGET, environment, verify_sdk
from build_katago_macos_dependencies import digest
from package_katago_source_linux import inspect_elf

VENDOR_LIBRARIES = frozenset({
    "libcudart.so.12", "libcublas.so.12", "libcublasLt.so.12", "libnvblas.so.12",
    "libnvrtc.so.12", "libnvrtc-builtins.so.12.1", "libnvJitLink.so.12",
    "libcudnn.so.9", "libcudnn_adv.so.9", "libcudnn_cnn.so.9",
    "libcudnn_engines_precompiled.so.9", "libcudnn_engines_runtime_compiled.so.9",
    "libcudnn_graph.so.9", "libcudnn_heuristic.so.9", "libcudnn_ops.so.9",
})
DRIVER_LIBRARIES = frozenset({"libcuda.so.1"})


def audit_binary(binary: Path) -> dict:
    texts = [subprocess.check_output(["readelf", flag, str(binary)], text=True)
             for flag in ("-h", "-d", "--version-info")]
    # The prior official AppImage payload needs at most these ABI versions.
    audit = inspect_elf(*texts, "2.34", VENDOR_LIBRARIES | DRIVER_LIBRARIES)
    for family, ceiling in (("GLIBCXX", (3, 4, 30)), ("CXXABI", (1, 3, 13))):
        versions = set(re.findall(r"\b" + family + r"_([0-9]+(?:\.[0-9]+)+)\b", texts[2]))
        if any(tuple(map(int, version.split("."))) > ceiling for version in versions):
            raise ValueError(f"Build raised the prior Linux {family} ceiling")
        audit[family + "Versions"] = sorted(versions)
    return audit


def external_runtime(sdk: Path, executable_audit: dict) -> dict:
    records = {}
    audits = {}
    for name in sorted(VENDOR_LIBRARIES):
        matches = [sdk / part / name for part in ("cuda/lib", "cudnn/lib")
                   if (sdk / part / name).is_file()]
        if len(matches) != 1:
            raise ValueError(f"Missing or ambiguous locked external CUDA library: {name}")
        path = matches[0]
        if not path.resolve().is_relative_to(sdk.resolve()):
            raise ValueError("External CUDA library escapes its SDK")
        records[name] = file_record(path, sdk)
        audits[name] = audit_binary(path)
    for audit in [executable_audit, *audits.values()]:
        missing = (set(audit["needed"]) & VENDOR_LIBRARIES) - records.keys()
        if missing:
            raise ValueError(f"External CUDA dependency closure is incomplete: {sorted(missing)}")
    return {"mode": "external", "libraries": records, "elfAudit": audits,
            "driverLibraries": sorted(DRIVER_LIBRARIES), "gpuExecutionStatus": "NOT_RUN"}


def package(build: Path, sdk: Path, output: Path) -> dict:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("Linux CUDA packaging requires native Linux/x86_64")
    build, sdk, output = build.resolve(), sdk.resolve(), output.resolve()
    original = json.loads((build / "source-build.json").read_text(encoding="utf-8"))
    verified = verify_sdk(sdk)
    if (original.get("target") != TARGET or original.get("sourceCommit") != SOURCE_COMMIT
            or original.get("origin") != "project-source-build" or original.get("buildStatus") != "PASS"
            or original.get("backend") != "CUDA" or original.get("dependencyLockSha256") != digest(LOCK_PATH)
            or original.get("sdkReceipt") != file_record(sdk / "sdk-receipt.json", sdk)
            or original.get("executable") != file_record(build / "katago", build)):
        raise ValueError("Build or SDK identity changed before CUDA packaging")
    output.mkdir(parents=True, exist_ok=False)
    receipt = dict(original, packagingStatus="FAIL", dependencyAuditStatus="FAIL",
                   hardwareAcceptanceStatus="NOT_RUN", productionAbiAcceptanceStatus="NOT_RUN")
    try:
        shutil.copy2(build / "katago", output / "katago")
        (output / "katago").chmod(0o755)
        shutil.copytree(build / "source-licenses", output / "licenses/KataGo")
        shutil.copytree(sdk / "share/licenses", output / "licenses/dependencies")
        audit = audit_binary(output / "katago")
        if not {"libcublas.so.12", "libcudnn.so.9", "libnvrtc.so.12"} <= set(audit["needed"]):
            raise ValueError("CUDA engine did not link the intended runtime")
        runtime = external_runtime(sdk, audit)
        result = subprocess.run([str(output / "katago"), "version"], env=environment(sdk), cwd=output,
                                check=True, capture_output=True, text=True, timeout=30)
        if result.stdout != original["versionOutput"]:
            raise ValueError("Relocated CUDA engine identity changed")
        receipt.update(packagingStatus="PASS", dependencyAuditStatus="PASS", elfAudit=audit,
                       externalRuntime=runtime, versionExecution="locked-external-sdk-no-gpu",
                       executable=file_record(output / "katago", output),
                       files=[file_record(path, output) for path in sorted(output.rglob("*")) if path.is_file()],
                       dependencies=verified["dependencies"])
        receipt["productionAbiAcceptanceReason"] = (
            "ELF ceilings match the prior official CUDA payload; supported-distribution and final-package acceptance remain required"
        )
    except Exception as error:
        receipt["error"] = str(error)
        raise
    finally:
        (output / "source-package.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", required=True, type=Path)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    print(json.dumps(package(args.build, args.sdk, args.output), indent=2))
