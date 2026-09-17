#!/usr/bin/env python3
"""Build a sealed Windows CUDA SDK without changing the shipped NVIDIA runtime."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import zipfile

import build_katago_windows_dependencies as common
from build_katago_directml_dependencies import copy_member
from build_katago_macos_dependencies import digest, inventory

LOCK_PATH = Path(__file__).with_name("katago_cuda_dependencies.json")
TARGET = "windows-nvidia"


def runtime_files() -> list[str]:
    return json.loads(LOCK_PATH.read_text(encoding="utf-8"))["runtimeFiles"]


def engine_options(prefix: Path) -> list[str]:
    prefix = prefix.resolve()
    return [option for option in common.engine_options(prefix, "windows-cpu")
            if not option.startswith("-DEIGEN3_INCLUDE_DIRS=")] + [
        f"-DCMAKE_CUDA_COMPILER={prefix / 'cuda/bin/nvcc.exe'}",
        f"-DCUDAToolkit_ROOT={prefix / 'cuda'}",
        f"-DCUDNN_INCLUDE_DIR={prefix / 'cudnn/include'}",
        f"-DCUDNN_LIBRARY={prefix / 'cudnn/lib/x64/cudnn.lib'}",
    ]


def environment(prefix: Path) -> dict[str, str]:
    env = common.environment(prefix)
    for name in list(env):
        if name.upper().startswith("CUDA_PATH") or name.upper() in {
            "CUDACXX", "CUDAHOSTCXX", "CUDAFLAGS", "NVCC_PREPEND_FLAGS", "NVCC_APPEND_FLAGS",
        }:
            env.pop(name)
    env["PATH"] = os.pathsep.join((str(prefix / 'runtime'), str(prefix / 'cuda/bin'), env['PATH']))
    return env


def validated_entries(bundle: zipfile.ZipFile) -> list[tuple[str, PurePosixPath]]:
    entries = []
    seen = set()
    roots = set()
    for item in bundle.infolist():
        path = PurePosixPath(item.filename)
        if (not path.parts or path.is_absolute() or "\\" in item.filename
                or any(not part or part in {".", ".."} or ":" in part or part.endswith((" ", "."))
                       for part in item.filename.rstrip('/').split('/'))
                or (item.external_attr >> 16) & 0o170000 == 0o120000):
            raise ValueError(f"Unsafe CUDA archive path: {item.filename}")
        key = item.filename.rstrip('/').casefold()
        if key in seen:
            raise ValueError(f"Duplicate CUDA archive path: {item.filename}")
        seen.add(key)
        roots.add(path.parts[0])
        if not item.is_dir():
            if len(path.parts) < 2:
                raise ValueError("CUDA dependency must have a single directory root")
            entries.append((item.filename, PurePosixPath(*path.parts[1:])))
    if len(roots) != 1 or not entries:
        raise ValueError("CUDA dependency must have a single directory root")
    return entries


def install_archive(archive: Path, prefix: Path, dependency: dict) -> None:
    with zipfile.ZipFile(archive) as bundle:
        entries = validated_entries(bundle)
        licenses = []
        for member, relative in entries:
            if relative.parts[0].upper().startswith(("LICENSE", "NOTICE", "EULA")):
                target = prefix / 'share/licenses' / dependency['name'] / relative
                licenses.append(target)
            elif relative.parts[0] in {'include', 'lib', 'bin', 'nvvm'}:
                target = prefix / dependency['destination'] / relative
            else:
                continue
            if target.exists():
                raise ValueError(f"CUDA dependency would overwrite another component: {target}")
            copy_member(bundle, member, target)
            # Only runtime bin DLLs are distributed, never nvcc/nvvm compiler binaries.
            if relative.parts[0] == 'bin' and relative.suffix.lower() == '.dll':
                if len(relative.parts) != 2 or relative.name not in runtime_files():
                    raise ValueError(f"Undeclared CUDA runtime DLL: {relative}")
                copy_member(bundle, member, prefix / 'runtime' / relative.name)
        if not licenses:
            raise ValueError(f"CUDA redistribution notice missing: {dependency['name']}")


def install_runtime(archives: dict[str, Path], prefix: Path) -> None:
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    # Check every input before writing any provider files.
    for item in lock['dependencies']:
        path = archives[item['name']]
        if path.stat().st_size != item['sizeBytes'] or digest(path) != item['sha256']:
            raise ValueError(f"Dependency size or SHA-256 mismatch: {item['name']}")
    for item in lock['dependencies']:
        archive = archives[item['name']]
        if item['name'] == 'baseline-runtime':
            with zipfile.ZipFile(archive) as bundle:
                for name in runtime_files():
                    if name.startswith(('msvcp', 'vcruntime')):
                        copy_member(bundle, name, prefix / 'runtime' / name)
        else:
            install_archive(archive, prefix, item)
    actual = {path.name for path in (prefix / 'runtime').iterdir()}
    if actual != set(runtime_files()):
        raise ValueError(f"CUDA runtime inventory mismatch: {sorted(actual ^ set(runtime_files()))}")
    for name in ('cuda/bin/nvcc.exe', 'cuda/nvvm/libdevice/libdevice.10.bc',
                 'cuda/include/cuda_runtime.h', 'cuda/include/cub/cub.cuh',
                 'cuda/lib/x64/cudart_static.lib', 'cuda/lib/x64/cublas.lib',
                 'cuda/lib/x64/nvrtc.lib', 'cudnn/include/cudnn.h', 'cudnn/lib/x64/cudnn.lib'):
        if not (prefix / name).is_file():
            raise ValueError(f"CUDA SDK component missing: {name}")


def verify_sdk(prefix: Path) -> dict:
    prefix = prefix.resolve()
    receipt = json.loads((prefix / 'sdk-receipt.json').read_text(encoding='utf-8'))
    lock = json.loads(LOCK_PATH.read_text(encoding='utf-8'))
    if (receipt.get('schemaVersion') != 1 or receipt.get('status') != 'PASS'
            or receipt.get('system') != 'Windows' or receipt.get('arch') != 'x86_64'
            or receipt.get('provider') != 'cuda' or receipt.get('lockSha256') != digest(LOCK_PATH)
            or receipt.get('commonLockSha256') != digest(common.LOCK_PATH)
            or receipt.get('nvccVersion') != lock['nvccVersion']
            or receipt.get('configuration') != engine_options(prefix)
            or not receipt.get('files') or receipt['files'] != inventory(prefix)):
        raise ValueError('CUDA SDK receipt or files do not match the locked build')
    return receipt


def build_sdk(output: Path, jobs: int) -> Path:
    output = output.resolve()
    lock = json.loads(LOCK_PATH.read_text(encoding='utf-8'))
    prefix = common.build_sdk(output, jobs)
    base = common.verify_sdk(prefix)
    receipt = dict(schemaVersion=1, status='FAIL', system='Windows', arch='x86_64', provider='cuda',
                   lockSha256=digest(LOCK_PATH), commonLockSha256=digest(common.LOCK_PATH),
                   commonSdk=base, configuration=engine_options(prefix))
    (prefix / 'sdk-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    try:
        with (output / 'build.log').open('a', encoding='utf-8') as log:
            archives = {}
            for item in lock['dependencies']:
                archive = output / (item['name'] + '.zip')
                subprocess.run(['curl.exe', '--fail', '--location', '--proto', '=https',
                                '--proto-redir', '=https', '--retry', '3', '--connect-timeout', '30',
                                '--max-time', '1200', '--output', str(archive), item['url']],
                               check=True, stdout=log, stderr=subprocess.STDOUT)
                archives[item['name']] = archive
            install_runtime(archives, prefix)
            version = subprocess.check_output([str(prefix / 'cuda/bin/nvcc.exe'), '--version'],
                                              text=True, env=environment(prefix), timeout=30)
            if not re.search(r'\bV' + re.escape(lock['nvccVersion']) + r'\b', version):
                raise ValueError('CUDA compiler version does not match dependency lock')
        for item in base['files']:
            path = prefix / item['file']
            if not path.is_file() or digest(path) != item['sha256'] or path.stat().st_size != item['sizeBytes']:
                raise ValueError(f"Common SDK changed during CUDA installation: {item['file']}")
        receipt.update(status='PASS', nvccVersion=lock['nvccVersion'], nvccVersionOutput=version,
                       files=inventory(prefix), dependencies=lock['dependencies'])
    except Exception as error:
        receipt['error'] = str(error)
        raise
    finally:
        (prefix / 'sdk-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    return prefix


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--jobs', type=int, default=3)
    args = parser.parse_args()
    print(build_sdk(args.output, args.jobs))
