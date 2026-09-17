#!/usr/bin/env python3
"""Build a pinned KataGo checkout; receipts are build evidence, not release approval."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import shutil

from probe_katago_focus import SOURCE_COMMIT
from build_katago_macos_dependencies import sdk_environment, verify_sdk


TARGETS = {
    "windows-cpu": ("Windows", "x86_64", "EIGEN", False),
    "windows-opencl": ("Windows", "x86_64", "OPENCL", False),
    "windows-nvidia": ("Windows", "x86_64", "CUDA", False),
    "windows-tensorrt": ("Windows", "x86_64", "TENSORRT", False),
    "windows-directml": ("Windows", "x86_64", "ONNX", True),
    "windows-openvino": ("Windows", "x86_64", "ONNX", True),
    "windows-rocm-gfx103x": ("Windows", "x86_64", "ROCM", True),
    "windows-rocm-gfx110x": ("Windows", "x86_64", "ROCM", True),
    "windows-rocm-gfx1151": ("Windows", "x86_64", "ROCM", True),
    "windows-rocm-gfx120x": ("Windows", "x86_64", "ROCM", True),
    "linux-cpu": ("Linux", "x86_64", "EIGEN", False),
    "linux-opencl": ("Linux", "x86_64", "OPENCL", False),
    "linux-nvidia": ("Linux", "x86_64", "CUDA", False),
    "macos-arm64": ("Darwin", "arm64", "METAL", False),
    "macos-amd64": ("Darwin", "x86_64", "METAL", False),
}
ARCH_ALIASES = {"amd64": "x86_64", "aarch64": "arm64"}
MACOS_MINIMUM_VERSION = "15.0"
SDK_CACHE_KEYS = {
    "CMAKE_PREFIX_PATH", "CMAKE_TOOLCHAIN_FILE", "CUDAToolkit_ROOT", "CUDA_TOOLKIT_ROOT_DIR",
    "CUDNN_INCLUDE_DIR", "CUDNN_LIBRARY", "TENSORRT_INCLUDE_DIR", "TENSORRT_LIBRARY",
    "TENSORRT_ROOT_DIR", "TENSORRT_ONNXPARSER_LIBRARY", "ONNXRUNTIME_ROOT", "Protobuf_ROOT", "Eigen3_DIR",
    "ZLIB_INCLUDE_DIR", "ZLIB_LIBRARY", "HIP_ROOT_DIR", "CMAKE_HIP_COMPILER",
    "CMAKE_HIP_ARCHITECTURES", "CMAKE_CUDA_ARCHITECTURES",
}


def checked(*command: str, cwd: Path | None = None) -> str:
    return subprocess.run(command, cwd=cwd, check=True, capture_output=True, text=True).stdout.strip()


def check_source(source: Path) -> None:
    if checked("git", "rev-parse", "HEAD", cwd=source) != SOURCE_COMMIT:
        raise ValueError("source HEAD must equal the pinned merged upstream commit")
    if checked("git", "status", "--porcelain", "--untracked-files=all", cwd=source):
        raise ValueError("source checkout must be clean, including untracked files")
    if not (source / "cpp/CMakeLists.txt").is_file():
        raise ValueError("KataGo CMake project missing")


def configuration(target: str, sdk: list[str]) -> list[str]:
    system, arch, backend, _ = TARGETS[target]
    options = [
        "-DCMAKE_BUILD_TYPE=Release", f"-DUSE_BACKEND={backend}", "-DBUILD_DISTRIBUTED=0",
        "-DNO_GIT_REVISION=0", "-DKATAGO_AUTO_FETCH_DEPS=OFF",
    ]
    if system == "Darwin":
        options.append(f"-DCMAKE_OSX_ARCHITECTURES={arch}")
        options.append(f"-DCMAKE_OSX_DEPLOYMENT_TARGET={MACOS_MINIMUM_VERSION}")
        # Upstream's Swift linker does not inherit the C++ deployment target.
        options.append(
            f"-DCMAKE_Swift_FLAGS=-target {arch}-apple-macosx{MACOS_MINIMUM_VERSION} "
            "-Xlinker -headerpad_max_install_names"
        )
    if backend == "TENSORRT":
        options.append("-DUSE_CACHE_TENSORRT_PLAN=1")
    for option in sdk:
        key, separator, value = option.partition("=")
        if not separator or key not in SDK_CACHE_KEYS or not value or "\n" in value:
            raise ValueError(f"unsupported SDK setting: {key}")
        options.append(f"-D{key}={value}")
    return options


def check_host(target: str) -> None:
    system, arch, _, _ = TARGETS[target]
    actual_arch = platform.machine().lower()
    actual_arch = ARCH_ALIASES.get(actual_arch, actual_arch)
    if platform.system() != system or actual_arch != arch:
        raise ValueError(f"{target} requires native {system}/{arch}, not this host")


def file_record(path: Path, root: Path) -> dict:
    with path.open("rb") as handle:
        digest = hashlib.file_digest(handle, "sha256").hexdigest()
    return {"file": str(path.relative_to(root)), "sizeBytes": path.stat().st_size, "sha256": digest}


def copy_source_licenses(source: Path, output: Path) -> None:
    paths = [source / "LICENSE"]
    for path in (source / "cpp/external").rglob("*"):
        if path.is_file() and path.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")):
            paths.append(path)
    for path in paths:
        destination = output / "source-licenses" / path.relative_to(source)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, destination)


def macos_minimum_version(load_commands: str) -> str:
    matches = re.findall(r"(?m)^\s*minos\s+(\d+(?:\.\d+){0,2})\s*$", load_commands)
    if len(matches) != 1:
        raise ValueError("missing or ambiguous macOS minimum-version load command")
    actual = tuple(int(part) for part in matches[0].split("."))
    actual += (0,) * (3 - len(actual))
    maximum = tuple(int(part) for part in MACOS_MINIMUM_VERSION.split(".")) + (0,)
    if actual > maximum:
        raise ValueError(f"binary raises minimum macOS version to {matches[0]}")
    return matches[0]


def macos_sdk_options(prefix: Path) -> list[str]:
    prefix = prefix.resolve()
    return [
        f"-DCMAKE_PREFIX_PATH={prefix}", "-DCMAKE_IGNORE_PREFIX_PATH=/opt/homebrew;/usr/local",
        "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF", "-DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF",
        f"-DPKG_CONFIG_EXECUTABLE={shutil.which('pkg-config') or 'pkg-config'}",
        f"-DProtobuf_INCLUDE_DIR={prefix / 'include'}",
        f"-DProtobuf_LIBRARY_RELEASE={prefix / 'lib/libprotobuf.dylib'}",
        f"-DProtobuf_PROTOC_EXECUTABLE={prefix / 'bin/protoc'}",
        f"-DLIBZIP_LIBRARY={prefix / 'lib/libzip.dylib'}",
        f"-DLIBZIP_INCLUDE_DIR_ZIP={prefix / 'include'}",
        f"-DLIBZIP_INCLUDE_DIR_ZIPCONF={prefix / 'include'}",
    ]


def build(source: Path, output: Path, target: str, sdk: list[str], jobs: int,
          macos_sdk: Path | None = None) -> dict:
    check_host(target)
    source, output = source.resolve(), output.resolve()
    check_source(source)
    if output == source or source in output.parents:
        raise ValueError("build output must be outside the source checkout")
    options = configuration(target, sdk)
    build_env = None
    sdk_receipt = None
    if TARGETS[target][0] == "Darwin":
        if macos_sdk is None:
            raise ValueError("macOS builds require --macos-sdk with verified pinned dependencies")
        if sdk:
            raise ValueError("macOS SDK settings cannot override pinned dependencies")
        sdk_receipt = verify_sdk(macos_sdk, TARGETS[target][1])
        options.extend(macos_sdk_options(macos_sdk))
        build_env = sdk_environment(macos_sdk.resolve())
    elif macos_sdk is not None:
        raise ValueError("--macos-sdk is only valid for macOS targets")
    # Never reuse a stale executable after a failed configure/build.
    output.mkdir(parents=True, exist_ok=False)
    result = {
        "schemaVersion": 1,
        "origin": "project-source-build",
        "sourceRepository": "https://github.com/lightvector/KataGo",
        "sourceCommit": SOURCE_COMMIT,
        "target": target,
        "backend": TARGETS[target][2],
        "experimental": TARGETS[target][3],
        "configuration": options,
        "buildStatus": "FAIL",
        "packagingStatus": "NOT_RUN",
        "dependencyAuditStatus": "NOT_RUN",
        "hardwareAcceptanceStatus": "NOT_RUN",
    }
    if sdk_receipt is not None:
        result["dependencyLockSha256"] = sdk_receipt["lockSha256"]
        result["sdkReceipt"] = file_record(macos_sdk / "sdk-receipt.json", macos_sdk)
    try:
        with (output / "build.log").open("w", encoding="utf-8") as log:
            for command in (
                ["cmake", "-S", str(source / "cpp"), "-B", str(output), "-G", "Ninja", *options],
                ["cmake", "--build", str(output), "--target", "katago", "--parallel", str(jobs)],
            ):
                subprocess.run(command, check=True, stdout=log, stderr=subprocess.STDOUT, env=build_env)
        check_source(source)
        binary = output / ("katago.exe" if os.name == "nt" else "katago")
        if TARGETS[target][0] == "Darwin":
            result["macOSMinimumVersion"] = macos_minimum_version(
                checked("otool", "-l", str(binary))
            )
        version = subprocess.run(
            [str(binary), "version"], check=True, capture_output=True, text=True, timeout=30
        ).stdout
        if f"Git revision: {SOURCE_COMMIT}" not in version:
            raise ValueError("built executable does not report the requested source revision")
        expected_backend = {
            "EIGEN": "Eigen(CPU)", "OPENCL": "OpenCL", "METAL": "Metal",
            "TENSORRT": "TensorRT", "ROCM": "ROCm", "ONNX": "ONNX Runtime",
        }.get(
            TARGETS[target][2], TARGETS[target][2]
        )
        if f"Using {expected_backend} backend" not in version:
            raise ValueError("built executable reports a different backend")
        compiler_files = list((output / "CMakeFiles").glob("*/CMakeCXXCompiler.cmake"))
        if len(compiler_files) != 1:
            raise ValueError("missing or ambiguous compiler identity")
        compiler = compiler_files[0].read_text(encoding="utf-8")
        compiler_info = {}
        for key in ("CMAKE_CXX_COMPILER_ID", "CMAKE_CXX_COMPILER_VERSION", "CMAKE_CXX_COMPILER"):
            match = re.search(rf'set\({key} "([^"]+)"\)', compiler)
            if match is None:
                raise ValueError(f"compiler identity missing {key}")
            compiler_info[key] = match.group(1)
        result.update(
            buildStatus="PASS", versionOutput=version, executable=file_record(binary, output),
            compiler=compiler_info, cmakeCache=file_record(output / "CMakeCache.txt", output),
        )
        copy_source_licenses(source, output)
        return result
    except Exception as error:
        result["buildStatus"] = "FAIL"
        result["error"] = str(error)
        raise
    finally:
        (output / "source-build.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def check_release_receipts(receipts: list[dict]) -> None:
    by_target = {}
    for receipt in receipts:
        target = receipt.get("target")
        if target not in TARGETS or target in by_target:
            raise ValueError(f"unknown or duplicate build target: {target}")
        if (receipt.get("schemaVersion") != 1
                or receipt.get("sourceRepository") != "https://github.com/lightvector/KataGo"
                or receipt.get("sourceCommit") != SOURCE_COMMIT
                or receipt.get("origin") != "project-source-build"
                or receipt.get("backend") != TARGETS[target][2]):
            raise ValueError(f"wrong source identity for {target}")
        for status in ("buildStatus", "packagingStatus", "dependencyAuditStatus"):
            if receipt.get(status) != "PASS":
                raise ValueError(f"{target}: {status} must pass")
        if receipt.get("hardwareAcceptanceStatus") not in {"PASS", "PENDING_HARDWARE"}:
            raise ValueError(f"{target}: actual failure or missing hardware acceptance record")
        if receipt["hardwareAcceptanceStatus"] == "PENDING_HARDWARE":
            if TARGETS[target][2] == "EIGEN":
                raise ValueError(f"{target}: CPU execution cannot be deferred as missing GPU hardware")
            if not str(receipt.get("hardwareAcceptanceReason", "")).strip():
                raise ValueError(f"{target}: pending hardware needs an explicit reason")
        executable = receipt.get("executable", {})
        file_name = executable.get("file", "")
        expected_name = "katago.exe" if TARGETS[target][0] == "Windows" else "katago"
        size = executable.get("sizeBytes")
        if (file_name != expected_name or type(size) is not int or size <= 0
                or not re.fullmatch(r"[0-9a-f]{64}", str(executable.get("sha256", "")))):
            raise ValueError(f"{target}: missing or invalid executable identity")
        by_target[target] = receipt
    if set(by_target) != set(TARGETS):
        raise ValueError("missing build targets: " + ", ".join(sorted(set(TARGETS) - set(by_target))))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--target", required=True, choices=TARGETS)
    parser.add_argument("--sdk", action="append", default=[])
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--macos-sdk", type=Path)
    args = parser.parse_args()
    if not 1 <= args.jobs <= 64:
        parser.error("jobs must be between 1 and 64")
    result = build(args.source, args.output, args.target, args.sdk, args.jobs, args.macos_sdk)
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
