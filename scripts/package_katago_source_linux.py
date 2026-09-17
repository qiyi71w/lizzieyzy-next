#!/usr/bin/env python3
"""Audit a Linux evidence bundle without approving production ABI compatibility."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import platform
import re
import shutil
import subprocess

from build_katago_source import SOURCE_COMMIT, file_record
from build_katago_linux_dependencies import LOCK_PATH, TARGETS, environment, verify_sdk
from build_katago_macos_dependencies import digest

SYSTEM_LIBRARIES = {"libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "librt.so.1",
                    "libstdc++.so.6", "libgcc_s.so.1", "ld-linux-x86-64.so.2"}


def inspect_elf(header: str, dynamic: str, versions: str, maximum_glibc: str,
                external_libraries: frozenset[str] = frozenset()) -> dict:
    if not re.search(r"Class:\s+ELF64\b", header) or not re.search(r"Machine:\s+Advanced Micro Devices X86-64", header):
        raise ValueError("Expected native ELF64/x86_64")
    needed = re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", dynamic)
    if any("/" in name or name not in SYSTEM_LIBRARIES | {"libOpenCL.so.1"} | external_libraries
           for name in needed):
        raise ValueError("Unapproved or absolute dynamic library dependency")
    rpaths = re.findall(r"\((?:RUNPATH|RPATH)\).*?\[([^\]]*)\]", dynamic)
    if any(path not in {"", "$ORIGIN"} for paths in rpaths for path in paths.split(":")):
        raise ValueError("Build-host path escaped into Linux runtime search path")
    glibc = set(re.findall(r"\bGLIBC_([0-9]+(?:\.[0-9]+)+)\b", versions))
    limit = tuple(map(int, maximum_glibc.split(".")))
    if any(tuple(map(int, version.split("."))) > limit for version in glibc):
        raise ValueError("Build raised the configured glibc ceiling")
    return {"needed": needed, "rpaths": rpaths, "glibcVersions": sorted(glibc)}


def package(build: Path, sdk: Path, output: Path) -> dict:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("Linux packaging needs a native Linux/x86_64 host")
    build, sdk, output = build.resolve(), sdk.resolve(), output.resolve()
    original = json.loads((build / "source-build.json").read_text(encoding="utf-8"))
    verified_sdk = verify_sdk(sdk)
    target = original.get("target")
    if (target not in TARGETS or original.get("sourceCommit") != SOURCE_COMMIT
            or original.get("origin") != "project-source-build" or original.get("buildStatus") != "PASS"
            or original.get("dependencyLockSha256") != digest(LOCK_PATH)
            or original.get("sdkReceipt") != file_record(sdk / "sdk-receipt.json", sdk)
            or original.get("executable") != file_record(build / "katago", build)):
        raise ValueError("Build or SDK identity changed before packaging")
    output.mkdir(parents=True, exist_ok=False)
    receipt = dict(original, packagingStatus="FAIL", dependencyAuditStatus="FAIL",
                   hardwareAcceptanceStatus="NOT_RUN", productionAbiAcceptanceStatus="NOT_RUN")
    try:
        shutil.copy2(build / "katago", output / "katago")
        (output / "katago").chmod(0o755)
        shutil.copytree(build / "source-licenses", output / "licenses/KataGo")
        shutil.copytree(sdk / "share/licenses", output / "licenses/dependencies")
        if target == "linux-opencl":
            shutil.copy2(sdk / "lib/libOpenCL.so.1", output / "libOpenCL.so.1", follow_symlinks=True)
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        audits = {}
        for binary in sorted(output.iterdir()):
            if not binary.is_file():
                continue
            texts = [subprocess.check_output(["readelf", flag, str(binary)], text=True)
                     for flag in ("-h", "-d", "--version-info")]
            audit = inspect_elf(*texts, lock["maximumGlibc"])
            if "libOpenCL.so.1" in audit["needed"] and not (output / "libOpenCL.so.1").is_file():
                raise ValueError("OpenCL loader missing from portable bundle")
            if binary.name == "katago" and target == "linux-opencl" and "$ORIGIN" not in audit["rpaths"]:
                raise ValueError("OpenCL bundle must resolve its own loader")
            audits[binary.name] = audit
        env = environment(output)
        result = subprocess.run([str(output / "katago"), "version"], env=env, cwd=output,
                                check=True, capture_output=True, text=True, timeout=30)
        if result.stdout != original["versionOutput"]:
            raise ValueError("Portable engine identity differs after packaging")
        receipt.update(packagingStatus="PASS", dependencyAuditStatus="PASS", elfAudit=audits,
                       executable=file_record(output / "katago", output),
                       files=[file_record(path, output) for path in sorted(output.rglob("*")) if path.is_file()],
                       dependencies=verified_sdk["dependencies"])
        # Build-host ABI validation is not proof that the old production package's minimum OS is preserved.
        receipt["productionAbiAcceptanceReason"] = "Compare against the extracted official AppImage and supported distributions before release"
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
