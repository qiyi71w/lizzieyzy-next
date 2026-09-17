#!/usr/bin/env python3
"""Prepare the existing DirectML runtime with locked headers and static protobuf."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

import build_katago_windows_dependencies as common
from build_katago_macos_dependencies import digest, extract_verified, inventory

LOCK_PATH = Path(__file__).with_name("katago_directml_dependencies.json")
TARGET = "windows-directml"


def protobuf_options(prefix: Path) -> list[str]:
    return [*common.common_options(prefix), "-Dprotobuf_BUILD_TESTS=OFF",
            "-Dprotobuf_BUILD_SHARED_LIBS=OFF", "-Dprotobuf_MSVC_STATIC_RUNTIME=ON",
            "-Dprotobuf_WITH_ZLIB=OFF"]


def engine_options(prefix: Path) -> list[str]:
    prefix = prefix.resolve()
    return [option for option in common.engine_options(prefix, "windows-cpu")
            if not option.startswith("-DEIGEN3_INCLUDE_DIRS=")] + [
        f"-DONNXRUNTIME_ROOT={prefix / 'ort'}", "-DProtobuf_USE_STATIC_LIBS=ON",
        f"-DProtobuf_INCLUDE_DIR={prefix / 'include'}",
        f"-DProtobuf_LIBRARY={prefix / 'lib/libprotobuf.lib'}",
        f"-DProtobuf_PROTOC_EXECUTABLE={prefix / 'bin/protoc.exe'}",
    ]


def runtime_files() -> list[str]:
    return json.loads(LOCK_PATH.read_text(encoding="utf-8"))["runtimeFiles"]


def copy_member(bundle: zipfile.ZipFile, member: str, destination: Path) -> None:
    # Do not extract package-controlled paths, symlinks, alternate streams, or duplicate entries.
    if len([item for item in bundle.infolist() if item.filename == member]) != 1:
        raise ValueError(f"Missing or duplicate dependency entry: {member}")
    info = bundle.getinfo(member)
    if info.is_dir() or (info.external_attr >> 16) & 0o170000 == 0o120000:
        raise ValueError(f"Dependency entry is not a regular file: {member}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with bundle.open(info) as source, destination.open("xb") as target:
        shutil.copyfileobj(source, target)


def install_runtime(archives: dict[str, Path], prefix: Path) -> None:
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    for dependency in lock["dependencies"]:
        if digest(archives[dependency["name"]]) != dependency["sha256"]:
            raise ValueError(f"Dependency SHA-256 mismatch: {dependency['name']}")
    with zipfile.ZipFile(archives["onnxruntime"]) as ort, \
            zipfile.ZipFile(archives["directml"]) as dml, \
            zipfile.ZipFile(archives["baseline-runtime"]) as baseline:
        for name in ort.namelist():
            if name.startswith("build/native/include/") and name.endswith(".h"):
                leaf = name.removeprefix("build/native/include/")
                if not leaf or any(character in leaf for character in "/\\:"):
                    raise ValueError("Unexpected ONNX Runtime header path")
                copy_member(ort, name, prefix / "ort/include" / leaf)
        for name in ("onnxruntime.lib", "onnxruntime.dll", "onnxruntime_providers_shared.dll"):
            member = "runtimes/win-x64/native/" + name
            copy_member(ort, member, prefix / "ort/lib" / name)
            if name.endswith(".dll"):
                if ort.read(member) != baseline.read(name):
                    raise ValueError(f"Runtime must remain identical to the current bundle: {name}")
                copy_member(ort, member, prefix / "runtime" / name)
        if dml.read("bin/x64-win/DirectML.dll") != baseline.read("DirectML.dll"):
            raise ValueError("DirectML runtime differs from the current bundle")
        copy_member(dml, "bin/x64-win/DirectML.dll", prefix / "runtime/DirectML.dll")
        for name in runtime_files():
            if name.startswith(("msvcp", "vcruntime")):
                copy_member(baseline, name, prefix / "runtime" / name)
        for name in ("LICENSE", "ThirdPartyNotices.txt"):
            copy_member(ort, name, prefix / "share/licenses/onnxruntime" / name)
        for name in ("LICENSE.txt", "LICENSE-CODE.txt", "ThirdPartyNotices.txt"):
            copy_member(dml, name, prefix / "share/licenses/directml" / name)
    if {path.name for path in (prefix / "runtime").iterdir()} != set(runtime_files()):
        raise ValueError("DirectML runtime inventory is incomplete or contains unexpected files")


def verify_sdk(prefix: Path) -> dict:
    return verify_provider_sdk(prefix, LOCK_PATH)


def verify_provider_sdk(prefix: Path, lock_path: Path) -> dict:
    prefix = prefix.resolve()
    provider = json.loads(lock_path.read_text(encoding="utf-8"))["provider"]
    receipt = json.loads((prefix / "sdk-receipt.json").read_text(encoding="utf-8"))
    if (receipt.get("schemaVersion") != 1 or receipt.get("status") != "PASS"
            or receipt.get("system") != "Windows" or receipt.get("arch") != "x86_64"
            or receipt.get("provider") != provider
            or receipt.get("lockSha256") != digest(lock_path)
            or receipt.get("commonLockSha256") != digest(common.LOCK_PATH)
            or receipt.get("configuration") != engine_options(prefix)
            or not receipt.get("files") or receipt["files"] != inventory(prefix)):
        raise ValueError(f"{provider} SDK receipt or files do not match the locked build")
    return receipt


def build_sdk(output: Path, jobs: int) -> Path:
    return build_provider_sdk(output, jobs, LOCK_PATH, install_runtime)


def build_provider_sdk(output: Path, jobs: int, lock_path: Path, installer) -> Path:
    output = output.resolve()
    lock = json.loads(lock_path.read_text(encoding="utf-8"))
    # This enforces the native host, toolchain and empty destination before any downloads.
    prefix = common.build_sdk(output, jobs)
    base_receipt = common.verify_sdk(prefix)
    receipt = {"schemaVersion": 1, "status": "FAIL", "system": "Windows", "arch": "x86_64",
               "provider": lock["provider"], "lockSha256": digest(lock_path),
               "commonLockSha256": digest(common.LOCK_PATH), "commonSdk": base_receipt,
               "configuration": engine_options(prefix)}
    # Never leave a passing common-SDK receipt over a partially installed provider SDK.
    (prefix / "sdk-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    try:
        with (output / "build.log").open("a", encoding="utf-8") as log:
            archives = {}
            for dependency in lock["dependencies"]:
                archive = output / (dependency["name"] + ".archive")
                subprocess.run(["curl.exe", "--fail", "--location", "--proto", "=https",
                                "--proto-redir", "=https", "--retry", "3", "--connect-timeout", "30",
                                "--max-time", "600", "--output", str(archive), dependency["url"]],
                               check=True, stdout=log, stderr=subprocess.STDOUT)
                if digest(archive) != dependency["sha256"]:
                    raise ValueError(f"Dependency SHA-256 mismatch: {dependency['name']}")
                archives[dependency["name"]] = archive
            protobuf = next(item for item in lock["dependencies"] if item["name"] == "protobuf")
            source = extract_verified(archives["protobuf"], output / "protobuf-source", protobuf["sha256"])
            for command in (
                ["cmake", "-S", str(source), "-B", str(output / "protobuf-build"), "-G", "Ninja",
                 *protobuf_options(prefix)],
                ["cmake", "--build", str(output / "protobuf-build"), "--parallel", str(jobs)],
                ["cmake", "--install", str(output / "protobuf-build")],
            ):
                subprocess.run(command, check=True, env=common.environment(prefix),
                               stdout=log, stderr=subprocess.STDOUT)
            (prefix / "share/licenses/protobuf").mkdir(parents=True)
            shutil.copy2(source / "LICENSE", prefix / "share/licenses/protobuf/LICENSE")
            installer(archives, prefix)
        for name in ("lib/libprotobuf.lib", "bin/protoc.exe", "ort/lib/onnxruntime.lib",
                     "ort/include/onnxruntime_cxx_api.h"):
            if not (prefix / name).is_file():
                raise ValueError(f"ONNX provider SDK component missing: {name}")
        # Supplement installation must not silently replace already-verified common dependencies.
        for item in base_receipt["files"]:
            path = prefix / item["file"]
            if not path.is_file() or digest(path) != item["sha256"] or path.stat().st_size != item["sizeBytes"]:
                raise ValueError(f"Common SDK changed during provider installation: {item['file']}")
        receipt.update(status="PASS", files=inventory(prefix), dependencies=lock["dependencies"])
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
