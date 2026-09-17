#!/usr/bin/env python3
"""Build a locked Windows CPU/OpenCL SDK under an explicit MSVC environment."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

from build_katago_macos_dependencies import digest, extract_verified, inventory

LOCK_PATH = Path(__file__).with_name("katago_windows_dependencies.json")
TARGETS = {"windows-cpu", "windows-opencl"}


def environment(prefix: Path) -> dict[str, str]:
    env = os.environ.copy()
    for name in ("CMAKE_PREFIX_PATH", "CMAKE_LIBRARY_PATH", "CMAKE_INCLUDE_PATH", "CPATH",
                 "CPLUS_INCLUDE_PATH", "LIBRARY_PATH", "CFLAGS", "CXXFLAGS", "LDFLAGS",
                 "PKG_CONFIG_PATH", "CL", "_CL_", "LINK", "_LINK_"):
        env.pop(name, None)
    env["PATH"] = str(prefix / "bin") + os.pathsep + env.get("PATH", "")
    return env


def common_options(prefix: Path) -> list[str]:
    return [
        "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF", "-DBUILD_TESTING=OFF",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5", "-DCMAKE_POLICY_DEFAULT_CMP0091=NEW",
        "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded", "-DCMAKE_INSTALL_LIBDIR=lib",
        f"-DCMAKE_INSTALL_PREFIX={prefix}", f"-DCMAKE_PREFIX_PATH={prefix}",
        "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF", "-DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF",
    ]


def verify_sdk(prefix: Path) -> dict:
    prefix = prefix.resolve()
    receipt = json.loads((prefix / "sdk-receipt.json").read_text(encoding="utf-8"))
    if (receipt.get("schemaVersion") != 1 or receipt.get("status") != "PASS"
            or receipt.get("system") != "Windows" or receipt.get("arch") != "x86_64"
            or receipt.get("lockSha256") != digest(LOCK_PATH)
            or receipt.get("configuration") != common_options(prefix)
            or not receipt.get("files") or receipt["files"] != inventory(prefix)):
        raise ValueError("Windows SDK receipt or files do not match the locked build")
    return receipt


def engine_options(prefix: Path, target: str) -> list[str]:
    if target not in TARGETS:
        raise ValueError("Windows SDK currently supports CPU and OpenCL only")
    prefix = prefix.resolve()
    options = [
        f"-DCMAKE_PREFIX_PATH={prefix}", f"-DCMAKE_FIND_ROOT_PATH={prefix}",
        "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY", "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY",
        "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY", "-DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER",
        "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF", "-DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF",
        "-DCMAKE_POLICY_DEFAULT_CMP0091=NEW", "-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded",
        f"-DZLIB_INCLUDE_DIR={prefix / 'include'}", f"-DZLIB_LIBRARY={prefix / 'lib/zlibstatic.lib'}",
        f"-DLIBZIP_INCLUDE_DIR_ZIP={prefix / 'include'}",
        f"-DLIBZIP_INCLUDE_DIR_ZIPCONF={prefix / 'include'}",
        f"-DLIBZIP_LIBRARY={prefix / 'lib/zip.lib'};{prefix / 'lib/zlibstatic.lib'}",
        "-DUSE_AVX2=OFF", "-DUSE_TCMALLOC=OFF",
    ]
    if target == "windows-cpu":
        options.append(f"-DEIGEN3_INCLUDE_DIRS={prefix / 'include/eigen3'}")
    else:
        options.extend([f"-DOpenCL_INCLUDE_DIR={prefix / 'include'}",
                        f"-DOpenCL_LIBRARY={prefix / 'lib/OpenCL.lib'}"])
    return options


def install_eigen_headers(source: Path, prefix: Path) -> None:
    # Eigen is header-only here. Its CMake install also probes unrelated Fortran BLAS targets.
    for name in ("Eigen", "unsupported"):
        if not (source / name).is_dir():
            raise ValueError(f"Eigen headers missing: {name}")
        shutil.copytree(source / name, prefix / "include/eigen3" / name)


def build_sdk(output: Path, jobs: int) -> Path:
    if platform.system() != "Windows" or platform.machine().lower() not in {"amd64", "x86_64"}:
        raise ValueError("Windows SDK requires a native Windows/x86_64 host")
    if not 1 <= jobs <= 64:
        raise ValueError("jobs must be between 1 and 64")
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if not os.environ.get("VCToolsVersion", "").startswith(lock["msvcToolset"] + "."):
        raise ValueError("Run from the locked MSVC x64 developer environment")
    if os.environ.get("VSCMD_ARG_TGT_ARCH") != "x64":
        raise ValueError("MSVC target must be x64")
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    prefix = output / "prefix"
    prefix.mkdir()
    receipt = {"schemaVersion": 1, "system": "Windows", "arch": "x86_64", "status": "FAIL",
               "configuration": common_options(prefix), "lockSha256": digest(LOCK_PATH),
               "msvcToolset": os.environ["VCToolsVersion"],
               "windowsSdk": os.environ.get("WindowsSDKVersion", "")}
    env = environment(prefix)
    try:
        with (output / "build.log").open("w", encoding="utf-8") as log:
            for dependency in lock["dependencies"]:
                name = dependency["name"]
                print(f"Building locked {name} {dependency['version']}", flush=True)
                archive = output / (name + ".tar")
                subprocess.run(["curl.exe", "--fail", "--location", "--proto", "=https",
                                "--proto-redir", "=https", "--retry", "3", "--connect-timeout", "30",
                                "--max-time", "600", "--output", str(archive), dependency["url"]],
                               check=True, stdout=log, stderr=subprocess.STDOUT)
                source = extract_verified(archive, output / (name + "-source"), dependency["sha256"])
                build = output / (name + "-build")
                if name == "eigen":
                    install_eigen_headers(source, prefix)
                else:
                    for command in (
                        ["cmake", "-S", str(source), "-B", str(build), "-G", "Ninja",
                         *common_options(prefix), *dependency["cmake"]],
                        ["cmake", "--build", str(build), "--parallel", str(jobs)],
                        ["cmake", "--install", str(build)],
                    ):
                        subprocess.run(command, check=True, env=env, stdout=log, stderr=subprocess.STDOUT)
                licenses = prefix / "share/licenses" / name
                licenses.mkdir(parents=True)
                for path in source.iterdir():
                    if path.is_file() and path.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")):
                        shutil.copy2(path, licenses / path.name)
                if not list(licenses.iterdir()):
                    raise ValueError(f"No redistribution license found for {name}")
        for name in ("lib/zlibstatic.lib", "lib/zip.lib", "lib/OpenCL.lib", "bin/OpenCL.dll",
                     "include/eigen3/Eigen/Core"):
            if not (prefix / name).is_file():
                raise ValueError(f"SDK component missing: {name}")
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
