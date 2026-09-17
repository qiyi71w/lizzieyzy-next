#!/usr/bin/env python3
"""Audit PE dependencies and relocate Windows evidence bundles; do not publish."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess

from build_katago_source import SOURCE_COMMIT, file_record
from build_katago_windows_dependencies import LOCK_PATH, TARGETS, verify_sdk
from build_katago_macos_dependencies import digest

SYSTEM_LIBRARIES = {"kernel32.dll", "advapi32.dll", "user32.dll", "gdi32.dll", "shell32.dll",
                    "ole32.dll", "oleaut32.dll", "ws2_32.dll", "bcrypt.dll", "crypt32.dll",
                    "ntdll.dll", "secur32.dll", "version.dll", "cfgmgr32.dll", "shlwapi.dll"}


def inspect_pe(headers: str, dependencies: str) -> dict:
    if not re.search(r"8664 machine \(x64\)", headers, re.IGNORECASE):
        raise ValueError("Expected native PE/x64")
    needed = sorted(set(re.findall(r"(?im)^\s*([A-Za-z0-9_.-]+\.dll)\s*$", dependencies)))
    if not needed:
        raise ValueError("Missing PE import evidence")
    for name in needed:
        lowered = name.lower()
        if lowered not in SYSTEM_LIBRARIES | {"opencl.dll"} and not lowered.startswith("api-ms-win-"):
            raise ValueError(f"Unapproved PE dependency: {name}")
    return {"needed": needed}


def package(build: Path, sdk: Path, output: Path) -> dict:
    if platform.system() != "Windows" or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise ValueError("Windows packaging needs a native Windows/x86_64 host")
    build, sdk, output = build.resolve(), sdk.resolve(), output.resolve()
    original = json.loads((build / "source-build.json").read_text(encoding="utf-8"))
    verified_sdk = verify_sdk(sdk)
    target = original.get("target")
    if (target not in TARGETS or original.get("sourceCommit") != SOURCE_COMMIT
            or original.get("origin") != "project-source-build" or original.get("buildStatus") != "PASS"
            or original.get("dependencyLockSha256") != digest(LOCK_PATH)
            or original.get("sdkReceipt") != file_record(sdk / "sdk-receipt.json", sdk)
            or original.get("executable") != file_record(build / "katago.exe", build)):
        raise ValueError("Build or SDK identity changed before packaging")
    output.mkdir(parents=True, exist_ok=False)
    receipt = dict(original, packagingStatus="FAIL", dependencyAuditStatus="FAIL",
                   hardwareAcceptanceStatus="NOT_RUN")
    try:
        shutil.copy2(build / "katago.exe", output / "katago.exe")
        shutil.copytree(build / "source-licenses", output / "licenses/KataGo")
        shutil.copytree(sdk / "share/licenses", output / "licenses/dependencies")
        if target == "windows-opencl":
            shutil.copy2(sdk / "bin/OpenCL.dll", output / "OpenCL.dll")
        audits = {}
        for binary in sorted(output.iterdir()):
            if not binary.is_file():
                continue
            headers, dependencies = [subprocess.check_output(["dumpbin", flag, str(binary)], text=True)
                                     for flag in ("/headers", "/dependents")]
            audit = inspect_pe(headers, dependencies)
            if any(name.lower() == "opencl.dll" for name in audit["needed"]):
                if not (output / "OpenCL.dll").is_file():
                    raise ValueError("OpenCL loader missing from portable bundle")
            audits[binary.name] = audit
        # No build SDK or developer-tool PATH can supply an accidentally missing DLL.
        env = os.environ.copy()
        env["PATH"] = str(Path(env["SystemRoot"]) / "System32")
        result = subprocess.run([str(output / "katago.exe"), "version"], env=env, cwd=output,
                                check=True, capture_output=True, text=True, timeout=30)
        if result.stdout != original["versionOutput"]:
            raise ValueError("Portable engine identity differs after packaging")
        receipt.update(packagingStatus="PASS", dependencyAuditStatus="PASS", peAudit=audits,
                       executable=file_record(output / "katago.exe", output),
                       files=[file_record(path, output) for path in sorted(output.rglob("*")) if path.is_file()],
                       dependencies=verified_sdk["dependencies"])
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
