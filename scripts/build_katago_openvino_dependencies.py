#!/usr/bin/env python3
"""Lock existing OpenVINO runtime bytes and their matching ORT header revision."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

import build_katago_directml_dependencies as onnx
from build_katago_macos_dependencies import digest

LOCK_PATH = Path(__file__).with_name("katago_openvino_dependencies.json")
TARGET = "windows-openvino"
TOOLKIT_ROOT = "openvino_toolkit_windows_2026.2.1.21919.ede283a88e3_x86_64/"
IMPORT_SYMBOLS = ("OrtGetApiBase", "OrtSessionOptionsAppendExecutionProvider_CPU",
                  "OrtSessionOptionsAppendExecutionProvider_OpenVINO")
engine_options = onnx.engine_options


def runtime_files() -> list[str]:
    return json.loads(LOCK_PATH.read_text(encoding="utf-8"))["runtimeFiles"]


def import_definition(exports: str) -> str:
    # Only verified C exports are needed: C++ wrappers call through OrtGetApiBase.
    for symbol in IMPORT_SYMBOLS:
        if not re.search(rf"(?m)^\s*\d+\s+[0-9A-Fa-f]+\s+[0-9A-Fa-f]+\s+{symbol}\s*$", exports):
            raise ValueError(f"Expected ONNX Runtime export missing: {symbol}")
    return "LIBRARY onnxruntime.dll\nEXPORTS\n" + "\n".join(IMPORT_SYMBOLS) + "\n"


def stage_runtime(archives: dict[str, Path], prefix: Path) -> None:
    lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
    for dependency in lock["dependencies"]:
        if digest(archives[dependency["name"]]) != dependency["sha256"]:
            raise ValueError(f"Dependency SHA-256 mismatch: {dependency['name']}")
    with zipfile.ZipFile(archives["baseline-runtime"]) as baseline, \
            zipfile.ZipFile(archives["openvino"]) as toolkit:
        for name in runtime_files():
            if name == "OpenCL.dll":
                destination = prefix / "runtime/OpenCL.dll"
                destination.parent.mkdir(parents=True, exist_ok=True)
                with (prefix / "bin/OpenCL.dll").open("rb") as source, destination.open("xb") as output:
                    shutil.copyfileobj(source, output)
                continue
            if name.startswith(("openvino", "tbb")):
                member = TOOLKIT_ROOT + ("runtime/3rdparty/tbb/bin/" if name.startswith("tbb")
                                         else "runtime/bin/intel64/Release/") + name
                if toolkit.read(member) != baseline.read(name):
                    raise ValueError(f"OpenVINO runtime differs from current bundle: {name}")
            onnx.copy_member(baseline, name, prefix / "runtime" / name)
            if name.startswith("onnxruntime"):
                onnx.copy_member(baseline, name, prefix / "ort/lib" / name)
        licenses = [name for name in toolkit.namelist()
                    if name.startswith(TOOLKIT_ROOT + "docs/licensing/") and not name.endswith("/")]
        if not licenses or TOOLKIT_ROOT + "docs/licensing/LICENSE" not in licenses:
            raise ValueError("OpenVINO licensing inventory missing")
        for name in licenses:
            leaf = name.removeprefix(TOOLKIT_ROOT + "docs/licensing/")
            if not leaf or any(character in leaf for character in "/\\:"):
                raise ValueError("Unexpected licensing path")
            onnx.copy_member(toolkit, name, prefix / "share/licenses/openvino" / leaf)
        onnx.copy_member(toolkit, TOOLKIT_ROOT + "runtime/3rdparty/tbb/TBB-LICENSE",
                         prefix / "share/licenses/onetbb/LICENSE")
    for dependency in lock["dependencies"]:
        name = dependency["name"]
        if name.endswith(".h"):
            destination = prefix / "ort/include" / name
        elif name in {"LICENSE", "ThirdPartyNotices.txt"}:
            destination = prefix / "share/licenses/onnxruntime" / name
        else:
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        with destination.open("xb") as output, archives[name].open("rb") as source:
            shutil.copyfileobj(source, output)
    if {path.name for path in (prefix / "runtime").iterdir()} != set(runtime_files()):
        raise ValueError("OpenVINO runtime inventory mismatch")


def install_runtime(archives: dict[str, Path], prefix: Path) -> None:
    stage_runtime(archives, prefix)
    runtime = prefix / "ort/lib/onnxruntime.dll"
    exports = subprocess.check_output(["dumpbin", "/exports", str(runtime)], text=True)
    definition = prefix / "ort/lib/onnxruntime.def"
    definition.write_text(import_definition(exports), encoding="ascii")
    with (prefix.parent / "build.log").open("a", encoding="utf-8") as log:
        log.write(exports)
        log.flush()
        subprocess.run(["lib.exe", "/nologo", "/machine:x64", "/def:" + str(definition),
                        "/out:" + str(prefix / "ort/lib/onnxruntime.lib")], check=True,
                       stdout=log, stderr=subprocess.STDOUT)


def verify_sdk(prefix: Path) -> dict:
    return onnx.verify_provider_sdk(prefix, LOCK_PATH)


def build_sdk(output: Path, jobs: int) -> Path:
    return onnx.build_provider_sdk(output, jobs, LOCK_PATH, install_runtime)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--jobs", type=int, default=3)
    args = parser.parse_args()
    print(build_sdk(args.output, args.jobs))
