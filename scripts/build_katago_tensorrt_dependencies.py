#!/usr/bin/env python3
"""Extend the sealed CUDA SDK with the existing TensorRT 10.9 redistribution."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
import zipfile

import build_katago_cuda_dependencies as cuda
from build_katago_directml_dependencies import copy_member
from build_katago_macos_dependencies import digest, inventory

LOCK_PATH = Path(__file__).with_name("katago_tensorrt_dependencies.json")
TARGET = "windows-tensorrt"


def runtime_files() -> list[str]:
    return cuda.runtime_files() + json.loads(LOCK_PATH.read_text(encoding="utf-8"))["runtimeFiles"]


def environment(prefix: Path) -> dict[str, str]:
    return cuda.environment(prefix)


def engine_options(prefix: Path) -> list[str]:
    prefix = prefix.resolve()
    return [option for option in cuda.engine_options(prefix)
            if not option.startswith(("-DCUDNN_", "-DCMAKE_CUDA_COMPILER="))] + [
        f"-DTENSORRT_ROOT_DIR={prefix / 'tensorrt'}",
        f"-DTENSORRT_INCLUDE_DIR={prefix / 'tensorrt/include'}",
        f"-DTENSORRT_LIBRARY={prefix / 'tensorrt/lib/nvinfer_10.lib'}",
        f"-DTENSORRT_ONNXPARSER_LIBRARY={prefix / 'tensorrt/lib/nvonnxparser_10.lib'}",
    ]


def install_archive(archive: Path, prefix: Path, lock: dict) -> None:
    item = lock["archive"]
    if archive.stat().st_size != item["sizeBytes"] or digest(archive) != item["sha256"]:
        raise ValueError("TensorRT archive size or SHA-256 mismatch")
    expected = set(lock["runtimeFiles"])
    with zipfile.ZipFile(archive) as bundle:
        entries = cuda.validated_entries(bundle)
        actual = {path.name for _, path in entries if path.parts[0] == "lib" and path.suffix.lower() == ".dll"}
        if actual != expected:
            raise ValueError("TensorRT runtime inventory differs from lock")
        required = {"include/NvInfer.h", "include/NvInferVersion.h", "lib/nvinfer_10.lib",
                    "lib/nvonnxparser_10.lib", "doc/Acknowledgements.txt", "doc/Readme.txt"}
        if not required <= {path.as_posix() for _, path in entries}:
            raise ValueError("TensorRT headers, libraries or notices are missing")
        for member, relative in entries:
            if relative.parts[0] == "doc":
                target = prefix / "share/licenses/tensorrt" / relative
            elif relative.parts[0] == "include" or (relative.parts[0] == "lib" and relative.suffix.lower() == ".lib"):
                target = prefix / "tensorrt" / relative
            elif relative.parts[0] == "lib" and relative.suffix.lower() == ".dll":
                target = prefix / "runtime" / relative.name
            else:
                continue
            if target.exists():
                raise ValueError(f"TensorRT would overwrite an SDK file: {relative}")
            copy_member(bundle, member, target)
    version_header = (prefix / "tensorrt/include/NvInferVersion.h").read_text(encoding="utf-8")
    version = ".".join(re.search(r"#define\s+NV_TENSORRT_" + part + r"\s+(\d+)\b", version_header).group(1)
                       for part in ("MAJOR", "MINOR", "PATCH", "BUILD"))
    if version != lock["version"]:
        raise ValueError("TensorRT header version differs from lock")


def verify_sdk(prefix: Path) -> dict:
    prefix = prefix.resolve()
    receipt = json.loads((prefix / "sdk-receipt.json").read_text(encoding="utf-8"))
    if (receipt.get("schemaVersion") != 1 or receipt.get("status") != "PASS"
            or receipt.get("provider") != "tensorrt" or receipt.get("system") != "Windows"
            or receipt.get("arch") != "x86_64" or receipt.get("lockSha256") != digest(LOCK_PATH)
            or receipt.get("cudaLockSha256") != digest(cuda.LOCK_PATH)
            or receipt.get("commonLockSha256") != digest(cuda.common.LOCK_PATH)
            or receipt.get("configuration") != engine_options(prefix)
            or not receipt.get("files") or receipt["files"] != inventory(prefix)):
        raise ValueError("TensorRT SDK receipt or files do not match the locked build")
    return receipt


def build_sdk(output: Path, jobs: int) -> Path:
    output = output.resolve()
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    prefix = cuda.build_sdk(output, jobs)
    base = cuda.verify_sdk(prefix)
    receipt = dict(schemaVersion=1, status="FAIL", provider="tensorrt", system="Windows", arch="x86_64",
                   lockSha256=digest(LOCK_PATH), cudaLockSha256=digest(cuda.LOCK_PATH),
                   commonLockSha256=digest(cuda.common.LOCK_PATH), cudaSdk=base,
                   configuration=engine_options(prefix))
    (prefix / "sdk-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    try:
        archive = output / "tensorrt.zip"
        with (output / "build.log").open("a", encoding="utf-8") as log:
            subprocess.run(["curl.exe", "--fail", "--location", "--proto", "=https", "--proto-redir", "=https",
                            "--retry", "3", "--connect-timeout", "30", "--max-time", "1200",
                            "--output", str(archive), lock["archive"]["url"]], check=True,
                           stdout=log, stderr=subprocess.STDOUT)
        install_archive(archive, prefix, lock)
        for item in base["files"]:
            path = prefix / item["file"]
            if not path.is_file() or path.stat().st_size != item["sizeBytes"] or digest(path) != item["sha256"]:
                raise ValueError(f"CUDA SDK changed during TensorRT installation: {item['file']}")
        if {path.name for path in (prefix / "runtime").iterdir()} != set(runtime_files()):
            raise ValueError("Combined TensorRT runtime inventory differs from lock")
        receipt.update(status="PASS", files=inventory(prefix),
                       dependencies=base["dependencies"] + [lock["archive"]])
    except Exception as error:
        receipt["error"] = str(error)
        raise
    finally:
        (prefix / "sdk-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return prefix


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--jobs", type=int, default=3)
    args = parser.parse_args()
    print(build_sdk(args.output, args.jobs))
