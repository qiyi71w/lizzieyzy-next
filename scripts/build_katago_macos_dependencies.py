#!/usr/bin/env python3
"""Build locked macOS libraries without inheriting Homebrew's minimum OS."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile

from macos_katago_bundle import MINIMUM_MACOS_VERSION, audit_macos_deployment

LOCK_PATH = Path(__file__).with_name("katago_macos_dependencies.json")


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def extract_verified(archive: Path, destination: Path, sha256: str) -> Path:
    if digest(archive) != sha256:
        raise ValueError(f"Dependency SHA-256 mismatch: {archive.name}")
    destination.mkdir(parents=True, exist_ok=False)
    with tarfile.open(archive) as bundle:
        # The standard data filter rejects traversal, devices and escaping symlinks.
        bundle.extractall(destination, filter="data")
    roots = list(destination.iterdir())
    if len(roots) != 1 or not roots[0].is_dir() or roots[0].is_symlink():
        raise ValueError("Dependency archive must have a single source directory")
    return roots[0]


def sdk_environment(prefix: Path) -> dict[str, str]:
    env = os.environ.copy()
    for name in ("CMAKE_PREFIX_PATH", "CMAKE_LIBRARY_PATH", "CMAKE_INCLUDE_PATH",
                 "CPATH", "CPLUS_INCLUDE_PATH", "LIBRARY_PATH", "SDKROOT",
                 "CFLAGS", "CXXFLAGS", "LDFLAGS", "PKG_CONFIG_PATH"):
        env.pop(name, None)
    env["PATH"] = str(prefix / "bin") + os.pathsep + env.get("PATH", "")
    env["PKG_CONFIG_LIBDIR"] = str(prefix / "lib/pkgconfig")
    env["MACOSX_DEPLOYMENT_TARGET"] = MINIMUM_MACOS_VERSION
    return env


def cmake_options(prefix: Path, arch: str) -> list[str]:
    return [
        "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_CXX_STANDARD=17", "-DBUILD_SHARED_LIBS=ON",
        f"-DCMAKE_OSX_DEPLOYMENT_TARGET={MINIMUM_MACOS_VERSION}",
        f"-DCMAKE_OSX_ARCHITECTURES={arch}", f"-DCMAKE_INSTALL_PREFIX={prefix}",
        f"-DCMAKE_PREFIX_PATH={prefix}", "-DCMAKE_FIND_USE_PACKAGE_REGISTRY=OFF",
        "-DCMAKE_FIND_USE_SYSTEM_PACKAGE_REGISTRY=OFF",
        "-DCMAKE_IGNORE_PREFIX_PATH=/opt/homebrew;/usr/local",
        "-DCMAKE_INSTALL_NAME_DIR=@rpath", f"-DCMAKE_INSTALL_RPATH={prefix / 'lib'}",
        "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-headerpad_max_install_names",
    ]


def inventory(prefix: Path) -> list[dict]:
    records = []
    for path in sorted(prefix.rglob("*")):
        if path.is_file() and path.name != "sdk-receipt.json":
            if not path.resolve().is_relative_to(prefix.resolve()):
                raise ValueError(f"SDK file escapes its prefix: {path}")
            records.append({"file": path.relative_to(prefix).as_posix(),
                            "sha256": digest(path), "sizeBytes": path.stat().st_size})
    return records


def verify_sdk(prefix: Path, arch: str) -> dict:
    receipt = json.loads((prefix / "sdk-receipt.json").read_text(encoding="utf-8"))
    if (receipt.get("status") != "PASS" or receipt.get("arch") != arch
            or receipt.get("lockSha256") != digest(LOCK_PATH)
            or receipt.get("minimumMacOSVersion") != MINIMUM_MACOS_VERSION
            or receipt.get("configuration") != cmake_options(prefix.resolve(), arch)):
        raise ValueError("macOS SDK receipt does not match the locked build")
    if not receipt.get("files") or receipt["files"] != inventory(prefix):
        raise ValueError("macOS SDK files have changed since verification")
    return receipt


def build_sdk(output: Path, arch: str, jobs: int) -> Path:
    if platform.system() != "Darwin" or platform.machine() != arch:
        raise ValueError("macOS dependencies require a matching native host")
    if not 1 <= jobs <= 64:
        raise ValueError("jobs must be between 1 and 64")
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    prefix = output / "prefix"
    prefix.mkdir()
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if lock["minimumMacOSVersion"] != MINIMUM_MACOS_VERSION:
        raise ValueError("SDK and bundle minimum macOS versions disagree")
    receipt = {"schemaVersion": 1, "arch": arch, "lockSha256": digest(LOCK_PATH),
               "minimumMacOSVersion": MINIMUM_MACOS_VERSION, "status": "FAIL",
               "configuration": cmake_options(prefix, arch)}
    env = sdk_environment(prefix)
    try:
        with (output / "build.log").open("w", encoding="utf-8") as log:
            for dependency in lock["dependencies"]:
                name = dependency["name"]
                print(f"Building {name} {dependency['version']} for macOS {MINIMUM_MACOS_VERSION}", flush=True)
                archive = output / (name + ".tar")
                subprocess.run(["curl", "--fail", "--location", "--proto", "=https",
                                "--proto-redir", "=https", "--retry", "3", "--connect-timeout", "30",
                                "--max-time", "600", "--output", str(archive), dependency["url"]],
                               check=True, stdout=log, stderr=subprocess.STDOUT)
                source = extract_verified(archive, output / (name + "-source"), dependency["sha256"])
                build = output / (name + "-build")
                cmake_source = source / dependency.get("cmakeSubdirectory", ".")
                for command in (
                    ["cmake", "-S", str(cmake_source), "-B", str(build), "-G", "Ninja",
                     *cmake_options(prefix, arch), *dependency["cmake"]],
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
        versions = {}
        for library in sorted((prefix / "lib").glob("*.dylib")):
            if not library.is_symlink():
                versions[library.name] = audit_macos_deployment(library)
                arches = subprocess.check_output(["lipo", "-archs", str(library)], text=True).split()
                if arches != [arch]:
                    raise ValueError(f"Wrong SDK architecture for {library.name}: {arches}")
        if not versions:
            raise ValueError("No SDK libraries were built")
        receipt.update(status="PASS", libraries=versions, files=inventory(prefix), dependencies=lock["dependencies"])
    except Exception as error:
        receipt["error"] = str(error)
        raise
    finally:
        (prefix / "sdk-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return prefix


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--arch", choices=("arm64", "x86_64"), required=True)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    print(build_sdk(args.output, args.arch, args.jobs))


if __name__ == "__main__":
    main()
