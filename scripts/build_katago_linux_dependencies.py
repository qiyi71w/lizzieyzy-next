#!/usr/bin/env python3
"""Build an isolated, hash-locked Linux CPU/OpenCL SDK, not GPU drivers."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess

from build_katago_macos_dependencies import digest, extract_verified, inventory

LOCK_PATH = Path(__file__).with_name("katago_linux_dependencies.json")
TARGETS = {"linux-cpu", "linux-opencl"}


def environment(prefix: Path) -> dict[str, str]:
    env = os.environ.copy()
    for name in ("CMAKE_PREFIX_PATH", "CMAKE_LIBRARY_PATH", "CMAKE_INCLUDE_PATH", "CPATH",
                 "CPLUS_INCLUDE_PATH", "LIBRARY_PATH", "CFLAGS", "CXXFLAGS", "LDFLAGS",
                 "PKG_CONFIG_PATH", "LD_LIBRARY_PATH", "LD_PRELOAD"):
        env.pop(name, None)
    env["PATH"] = str(prefix / "bin") + os.pathsep + env.get("PATH", "")
    env["PKG_CONFIG_LIBDIR"] = str(prefix / "lib/pkgconfig")
    return env


def common_options(prefix: Path) -> list[str]:
    return [
        "-DCMAKE_BUILD_TYPE=Release", "-DBUILD_SHARED_LIBS=OFF", "-DBUILD_TESTING=OFF",
        "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5",
        "-DCMAKE_INSTALL_LIBDIR=lib", f"-DCMAKE_INSTALL_PREFIX={prefix}",
        f"-DCMAKE_PREFIX_PATH={prefix}", "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF",
        "-DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF",
    ]


def verify_sdk(prefix: Path) -> dict:
    prefix = prefix.resolve()
    receipt = json.loads((prefix / "sdk-receipt.json").read_text(encoding="utf-8"))
    if (receipt.get("schemaVersion") != 1 or receipt.get("status") != "PASS"
            or receipt.get("system") != "Linux" or receipt.get("arch") != "x86_64"
            or receipt.get("lockSha256") != digest(LOCK_PATH)
            or receipt.get("configuration") != common_options(prefix)
            or not receipt.get("files") or receipt["files"] != inventory(prefix)):
        raise ValueError("Linux SDK receipt or files do not match the locked build")
    return receipt


def engine_options(prefix: Path, target: str) -> list[str]:
    if target not in TARGETS:
        raise ValueError("Linux SDK currently supports CPU and OpenCL only")
    prefix = prefix.resolve()
    options = [
        f"-DCMAKE_PREFIX_PATH={prefix}", f"-DCMAKE_FIND_ROOT_PATH={prefix}",
        "-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY", "-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY",
        "-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY", "-DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER",
        "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF", "-DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF",
        f"-DZLIB_INCLUDE_DIR={prefix / 'include'}", f"-DZLIB_LIBRARY={prefix / 'lib/libz.a'}",
        f"-DLIBZIP_INCLUDE_DIR_ZIP={prefix / 'include'}",
        f"-DLIBZIP_INCLUDE_DIR_ZIPCONF={prefix / 'include'}",
        f"-DLIBZIP_LIBRARY={prefix / 'lib/libzip.a'}", "-DUSE_AVX2=OFF", "-DUSE_TCMALLOC=OFF",
        "-DCMAKE_BUILD_WITH_INSTALL_RPATH=ON", "-DCMAKE_INSTALL_RPATH=$ORIGIN",
    ]
    if target == "linux-cpu":
        options.append(f"-DEIGEN3_INCLUDE_DIRS={prefix / 'include/eigen3'}")
    else:
        options.extend([f"-DOpenCL_INCLUDE_DIR={prefix / 'include'}",
                        f"-DOpenCL_LIBRARY={prefix / 'lib/libOpenCL.so'}"])
    return options


def build_sdk(output: Path, jobs: int) -> Path:
    if platform.system() != "Linux" or platform.machine() != "x86_64":
        raise ValueError("Linux SDK requires a native Linux/x86_64 host")
    if not 1 <= jobs <= 64:
        raise ValueError("jobs must be between 1 and 64")
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    prefix = output / "prefix"
    prefix.mkdir()
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    receipt = {"schemaVersion": 1, "system": "Linux", "arch": "x86_64", "status": "FAIL",
               "configuration": common_options(prefix), "lockSha256": digest(LOCK_PATH)}
    env = environment(prefix)
    try:
        with (output / "build.log").open("w", encoding="utf-8") as log:
            for dependency in lock["dependencies"]:
                name = dependency["name"]
                print(f"Building locked {name} {dependency['version']}", flush=True)
                archive = output / (name + ".tar")
                subprocess.run(["curl", "--fail", "--location", "--proto", "=https",
                                "--proto-redir", "=https", "--retry", "3", "--connect-timeout", "30",
                                "--max-time", "600", "--output", str(archive), dependency["url"]],
                               check=True, stdout=log, stderr=subprocess.STDOUT)
                source = extract_verified(archive, output / (name + "-source"), dependency["sha256"])
                build = output / (name + "-build")
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
        for name in ("lib/libz.a", "lib/libzip.a", "lib/libOpenCL.so.1", "include/eigen3/Eigen/Core"):
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
