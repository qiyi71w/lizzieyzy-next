#!/usr/bin/env python3
"""Seal the Linux CUDA build SDK; do not install drivers or bundle vendor runtimes."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

import build_katago_linux_dependencies as common
from build_katago_macos_dependencies import digest, extract_verified, inventory

LOCK_PATH = Path(__file__).with_name("katago_linux_cuda_dependencies.json")
TARGET = "linux-nvidia"


def engine_options(prefix: Path) -> list[str]:
    prefix = prefix.resolve()
    return [option for option in common.engine_options(prefix, "linux-cpu")
            if not option.startswith("-DEIGEN3_INCLUDE_DIRS=")] + [
        f"-DCMAKE_CUDA_COMPILER={prefix / 'cuda/bin/nvcc'}",
        f"-DCUDAToolkit_ROOT={prefix / 'cuda'}",
        f"-DCUDNN_INCLUDE_DIR={prefix / 'cudnn/include'}",
        f"-DCUDNN_LIBRARY={prefix / 'cudnn/lib/libcudnn.so'}",
    ]


def environment(prefix: Path) -> dict[str, str]:
    prefix = prefix.resolve()
    env = common.environment(prefix)
    for name in list(env):
        if name.upper().startswith("CUDA_PATH") or name.upper() in {
            "CUDA_HOME", "CUDACXX", "CUDAHOSTCXX", "CUDAFLAGS",
            "NVCC_PREPEND_FLAGS", "NVCC_APPEND_FLAGS",
        }:
            env.pop(name)
    env["PATH"] = str(prefix / "cuda/bin") + os.pathsep + env["PATH"]
    env["LD_LIBRARY_PATH"] = os.pathsep.join(str(prefix / part) for part in ("cuda/lib", "cudnn/lib", "lib"))
    return env


def install_archive(archive: Path, prefix: Path, dependency: dict, scratch: Path) -> None:
    if archive.stat().st_size != dependency["sizeBytes"]:
        raise ValueError(f"Dependency size mismatch: {dependency['name']}")
    source = extract_verified(archive, scratch, dependency["sha256"])
    notices = []
    for path in sorted(source.rglob("*")):
        relative = path.relative_to(source)
        if not path.resolve().is_relative_to(source.resolve()):
            raise ValueError(f"CUDA archive link escapes component: {relative}")
        if path.is_symlink() and (path.is_dir() or not path.exists()):
            raise ValueError(f"CUDA archive contains a directory or dangling link: {relative}")
        if path.is_dir():
            continue
        if relative.parts[0].upper().startswith(("LICENSE", "NOTICE", "EULA")):
            target = prefix / "share/licenses" / dependency["name"] / relative
            notices.append(target)
        elif relative.parts[0] in {"include", "lib", "bin", "nvvm"}:
            target = prefix / dependency["destination"] / relative
        else:
            continue
        if target.exists() or target.is_symlink():
            raise ValueError(f"CUDA dependency would overwrite another component: {target}")
        target.parent.mkdir(parents=True, exist_ok=True)
        if path.is_symlink() and path.resolve().parent == path.parent.resolve():
            # Preserve same-directory SONAME aliases without duplicating multi-GB runtime data.
            target.symlink_to(path.resolve().name)
        else:
            shutil.copy2(path, target)
    if not notices:
        raise ValueError(f"CUDA redistribution notice missing: {dependency['name']}")


def verify_sdk(prefix: Path) -> dict:
    prefix = prefix.resolve()
    receipt = json.loads((prefix / "sdk-receipt.json").read_text(encoding="utf-8"))
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    if (receipt.get("schemaVersion") != 1 or receipt.get("status") != "PASS"
            or receipt.get("system") != "Linux" or receipt.get("arch") != "x86_64"
            or receipt.get("provider") != "cuda" or receipt.get("lockSha256") != digest(LOCK_PATH)
            or receipt.get("commonLockSha256") != digest(common.LOCK_PATH)
            or receipt.get("nvccVersion") != lock["nvccVersion"]
            or receipt.get("configuration") != engine_options(prefix)
            or not receipt.get("files") or receipt["files"] != inventory(prefix)):
        raise ValueError("Linux CUDA SDK receipt or files do not match the locked build")
    return receipt


def build_sdk(output: Path, jobs: int) -> Path:
    output = output.resolve()
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    prefix = common.build_sdk(output, jobs)
    base = common.verify_sdk(prefix)
    receipt = dict(schemaVersion=1, status="FAIL", system="Linux", arch="x86_64", provider="cuda",
                   lockSha256=digest(LOCK_PATH), commonLockSha256=digest(common.LOCK_PATH),
                   commonSdk=base, configuration=engine_options(prefix), runtimeMode="external")
    (prefix / "sdk-receipt.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    try:
        with (output / "build.log").open("a", encoding="utf-8") as log:
            for item in lock["dependencies"]:
                archive = output / (item["name"] + ".tar.xz")
                subprocess.run(["curl", "--fail", "--location", "--proto", "=https",
                                "--proto-redir", "=https", "--retry", "3", "--connect-timeout", "30",
                                "--max-time", "1200", "--output", str(archive), item["url"]],
                               check=True, stdout=log, stderr=subprocess.STDOUT)
                scratch = output / (item["name"] + "-extracted")
                install_archive(archive, prefix, item, scratch)
                shutil.rmtree(scratch)
            for name in ("cuda/bin/nvcc", "cuda/nvvm/libdevice/libdevice.10.bc",
                         "cuda/include/cuda_runtime.h", "cuda/include/cub/cub.cuh",
                         "cuda/lib/libcudart_static.a", "cuda/lib/libcublas.so", "cuda/lib/libnvrtc.so",
                         "cudnn/include/cudnn.h", "cudnn/lib/libcudnn.so"):
                if not (prefix / name).is_file():
                    raise ValueError(f"Linux CUDA SDK component missing: {name}")
            version = subprocess.check_output([str(prefix / "cuda/bin/nvcc"), "--version"],
                                              text=True, env=environment(prefix), timeout=30)
            if not re.search(r"\bV" + re.escape(lock["nvccVersion"]) + r"\b", version):
                raise ValueError("CUDA compiler version does not match dependency lock")
        for item in base["files"]:
            path = prefix / item["file"]
            if not path.is_file() or digest(path) != item["sha256"] or path.stat().st_size != item["sizeBytes"]:
                raise ValueError(f"Common SDK changed during CUDA installation: {item['file']}")
        receipt.update(status="PASS", nvccVersion=lock["nvccVersion"], nvccVersionOutput=version,
                       files=inventory(prefix), dependencies=lock["dependencies"])
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
