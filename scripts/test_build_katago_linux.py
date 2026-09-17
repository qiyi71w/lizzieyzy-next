#!/usr/bin/env python3

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from build_katago_linux_dependencies import (
    LOCK_PATH, build_sdk, common_options, engine_options, environment, verify_sdk,
)
from build_katago_macos_dependencies import digest, inventory
from build_katago_source import build
from package_katago_source_linux import inspect_elf


class LinuxSourceTest(unittest.TestCase):
    def test_lock_is_small_pinned_and_does_not_contain_vendor_drivers(self):
        lock = json.loads(LOCK_PATH.read_text())
        self.assertEqual({"zlib", "eigen", "libzip", "opencl-headers", "opencl-loader"},
                         {item["name"] for item in lock["dependencies"]})
        for item in lock["dependencies"]:
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(item["url"].startswith("https://"))
            self.assertIn(item["version"], item["url"])

    def test_sdk_overrides_cannot_choose_a_different_backend(self):
        for target in ("linux-cpu", "linux-opencl"):
            options = engine_options(Path("/tmp/Chinese path/sdk"), target)
            self.assertIn("-DCMAKE_INSTALL_RPATH=$ORIGIN", options)
            self.assertIn("-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY", options)
            self.assertIn("-DUSE_AVX2=OFF", options)
            self.assertFalse(any("USE_BACKEND=" in option for option in options))
        with self.assertRaises(ValueError):
            engine_options(Path("sdk"), "linux-nvidia")

    def test_loader_and_compiler_environment_is_not_inherited(self):
        with patch.dict(os.environ, {"LD_PRELOAD": "untrusted", "LD_LIBRARY_PATH": "host",
                                     "CXXFLAGS": "-march=native", "CMAKE_PREFIX_PATH": "host"}):
            env = environment(Path("/tmp/sdk"))
        for name in ("LD_PRELOAD", "LD_LIBRARY_PATH", "CXXFLAGS", "CMAKE_PREFIX_PATH"):
            self.assertNotIn(name, env)

    def test_sdk_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "header.h").write_text("valid")
            receipt = {"schemaVersion": 1, "status": "PASS", "system": "Linux", "arch": "x86_64",
                       "lockSha256": digest(LOCK_PATH), "configuration": common_options(root),
                       "files": inventory(root)}
            (root / "sdk-receipt.json").write_text(json.dumps(receipt))
            self.assertEqual("PASS", verify_sdk(root)["status"])
            (root / "header.h").write_text("changed")
            with self.assertRaisesRegex(ValueError, "receipt or files"):
                verify_sdk(root)

    def test_sdk_wrong_host_is_rejected_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "sdk"
            with patch("build_katago_linux_dependencies.platform.system", return_value="Darwin"):
                with self.assertRaises(ValueError):
                    build_sdk(output, 2)
            self.assertFalse(output.exists())

    def test_cpu_and_opencl_require_verified_sdk(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for target in ("linux-cpu", "linux-opencl"):
                with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                    with self.assertRaisesRegex(ValueError, "verified pinned dependencies"):
                        build(root / "source", root / "output", target, [], 2)
            self.assertFalse((root / "output").exists())

    def test_elf_dependencies_and_minimum_version(self):
        header = "Class: ELF64\nMachine: Advanced Micro Devices X86-64\n"
        dynamic = "(NEEDED) Shared library: [libc.so.6]\n(RUNPATH) Library runpath: [$ORIGIN]\n"
        self.assertEqual(["libc.so.6"], inspect_elf(header, dynamic, "GLIBC_2.34", "2.35")["needed"])
        for bad in ("/tmp/sdk/libc.so.6", "libcudart.so.12", "libunexpected.so"):
            with self.assertRaisesRegex(ValueError, "dependency"):
                inspect_elf(header, dynamic.replace("libc.so.6", bad), "GLIBC_2.34", "2.35")
        with self.assertRaisesRegex(ValueError, "search path"):
            inspect_elf(header, dynamic.replace("$ORIGIN", "/tmp/sdk"), "GLIBC_2.34", "2.35")
        with self.assertRaisesRegex(ValueError, "glibc ceiling"):
            inspect_elf(header, dynamic, "GLIBC_2.36", "2.35")
        with self.assertRaisesRegex(ValueError, "ELF64"):
            inspect_elf(header.replace("ELF64", "ELF32"), dynamic, "GLIBC_2.34", "2.35")


if __name__ == "__main__":
    unittest.main()
