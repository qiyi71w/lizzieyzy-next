#!/usr/bin/env python3

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from build_katago_windows_dependencies import (
    LOCK_PATH, build_sdk, common_options, engine_options, environment, install_eigen_headers, verify_sdk,
)
from build_katago_macos_dependencies import digest, inventory
from build_katago_source import build
from package_katago_source_windows import inspect_pe


class WindowsSourceTest(unittest.TestCase):
    def test_eigen_headers_do_not_configure_unrelated_fortran(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "source"
            for name in ("Eigen", "unsupported"):
                (source / name).mkdir(parents=True)
                (source / name / "Core").write_text("verified header")
            with patch("build_katago_windows_dependencies.subprocess.run") as command:
                install_eigen_headers(source, root / "sdk")
                command.assert_not_called()
            self.assertEqual("verified header", (root / "sdk/include/eigen3/Eigen/Core").read_text())
            with self.assertRaisesRegex(ValueError, "headers missing"):
                install_eigen_headers(root / "missing", root / "other")

    def test_lock_contains_exact_libraries_and_not_gpu_drivers(self):
        lock = json.loads(LOCK_PATH.read_text())
        self.assertEqual("14.44", lock["msvcToolset"])
        self.assertEqual({"zlib", "eigen", "libzip", "opencl-headers", "opencl-loader"},
                         {item["name"] for item in lock["dependencies"]})
        for item in lock["dependencies"]:
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(item["url"].startswith("https://"))
            self.assertIn(item["version"], item["url"])

    def test_options_keep_static_runtime_and_compatible_cpu(self):
        for target in ("windows-cpu", "windows-opencl"):
            options = engine_options(Path("Chinese path/sdk"), target)
            self.assertIn("-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded", options)
            self.assertIn("-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY", options)
            self.assertIn("-DUSE_AVX2=OFF", options)
            self.assertFalse(any("USE_BACKEND=" in value for value in options))
        with self.assertRaises(ValueError):
            engine_options(Path("sdk"), "windows-nvidia")

    def test_developer_environment_preserves_toolchain_not_extra_flags(self):
        with patch.dict(os.environ, {"CL": "/arch:AVX512", "LINK": "bad.lib", "LIB": "compiler-lib",
                                     "INCLUDE": "compiler-include", "CMAKE_PREFIX_PATH": "host"}):
            env = environment(Path("sdk"))
        self.assertNotIn("CL", env)
        self.assertNotIn("LINK", env)
        self.assertNotIn("CMAKE_PREFIX_PATH", env)
        self.assertEqual("compiler-lib", env["LIB"])
        self.assertEqual("compiler-include", env["INCLUDE"])

    def test_sdk_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "header.h").write_text("valid")
            receipt = {"schemaVersion": 1, "status": "PASS", "system": "Windows", "arch": "x86_64",
                       "lockSha256": digest(LOCK_PATH), "configuration": common_options(root),
                       "files": inventory(root)}
            (root / "sdk-receipt.json").write_text(json.dumps(receipt))
            self.assertEqual("PASS", verify_sdk(root)["status"])
            (root / "header.h").write_text("changed")
            with self.assertRaisesRegex(ValueError, "receipt or files"):
                verify_sdk(root)

    def test_wrong_host_is_rejected_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "sdk"
            with patch("build_katago_windows_dependencies.platform.system", return_value="Darwin"):
                with self.assertRaises(ValueError):
                    build_sdk(output, 2)
            self.assertFalse(output.exists())

    def test_wrong_msvc_environment_is_rejected_without_writing(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "sdk"
            with patch("build_katago_windows_dependencies.platform.system", return_value="Windows"), \
                    patch("build_katago_windows_dependencies.platform.machine", return_value="AMD64"), \
                    patch.dict(os.environ, {"VCToolsVersion": "14.50.00000"}):
                with self.assertRaisesRegex(ValueError, "locked MSVC"):
                    build_sdk(output, 2)
            self.assertFalse(output.exists())

    def test_windows_cannot_silently_use_host_sdk(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for target in ("windows-cpu", "windows-opencl"):
                with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                    with self.assertRaisesRegex(ValueError, "verified pinned dependencies"):
                        build(root / "source", root / "output", target, [], 2)
            self.assertFalse((root / "output").exists())

    def test_pe_requires_x64_and_closed_dependencies(self):
        header = "8664 machine (x64)\n"
        imports = "    KERNEL32.dll\n    OpenCL.dll\n"
        self.assertEqual(["KERNEL32.dll", "OpenCL.dll"], inspect_pe(header, imports)["needed"])
        for bad in ("zlib1.dll", "cudart64_12.dll", "VCRUNTIME140.dll", "unexpected.dll"):
            with self.assertRaisesRegex(ValueError, "Unapproved"):
                inspect_pe(header, imports + "    " + bad + "\n")
        with self.assertRaisesRegex(ValueError, "PE/x64"):
            inspect_pe("14C machine (x86)", imports)
        with self.assertRaisesRegex(ValueError, "Missing PE"):
            inspect_pe(header, "no imports")


if __name__ == "__main__":
    unittest.main()
