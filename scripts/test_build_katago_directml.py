#!/usr/bin/env python3

import json
from pathlib import Path
import tempfile
import unittest
import warnings
from unittest.mock import patch
import zipfile

import build_katago_directml_dependencies as sdk
from build_katago_macos_dependencies import digest, inventory
from build_katago_source import build
from package_katago_source_windows import (
    DIRECTML_SYSTEM_LIBRARIES, inspect_pe, verify_runtime_closure,
)


class DirectMlSourceTest(unittest.TestCase):
    def test_locks_existing_provider_versions_and_nuget_digest(self):
        lock = json.loads(sdk.LOCK_PATH.read_text())
        versions = {item["name"]: item["version"] for item in lock["dependencies"]}
        self.assertEqual({"protobuf": "3.21.12", "onnxruntime": "1.24.4", "directml": "1.15.4",
                          "baseline-runtime": "v1.18.1"}, versions)
        for item in lock["dependencies"]:
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(item["url"].startswith("https://"))
        self.assertEqual(11, len(set(lock["runtimeFiles"])))
        self.assertNotIn("katago.exe", lock["runtimeFiles"])

    def test_protobuf_is_static_and_matches_engine_msvc_runtime(self):
        self.assertIn("-Dprotobuf_MSVC_STATIC_RUNTIME=ON", sdk.protobuf_options(Path("sdk")))
        self.assertIn("-Dprotobuf_BUILD_SHARED_LIBS=OFF", sdk.protobuf_options(Path("sdk")))
        options = sdk.engine_options(Path("Chinese path/sdk"))
        self.assertIn("-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreaded", options)
        self.assertIn("-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY", options)
        self.assertIn("-DProtobuf_USE_STATIC_LIBS=ON", options)
        self.assertFalse(any("EIGEN3_INCLUDE_DIRS" in option for option in options))
        self.assertTrue(any(option.startswith("-DONNXRUNTIME_ROOT=") for option in options))

    def test_sdk_requires_both_locks_provider_configuration_and_exact_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "header.h").write_text("verified")
            original = {"schemaVersion": 1, "status": "PASS", "system": "Windows", "arch": "x86_64",
                        "provider": "directml", "lockSha256": digest(sdk.LOCK_PATH),
                        "commonLockSha256": digest(sdk.common.LOCK_PATH),
                        "configuration": sdk.engine_options(root), "files": inventory(root)}
            for key, value in ((None, None), ("status", "FAIL"), ("provider", "cpu"),
                               ("commonLockSha256", "wrong"), ("lockSha256", "wrong"),
                               ("configuration", []), ("files", [])):
                receipt = dict(original)
                if key:
                    receipt[key] = value
                (root / "sdk-receipt.json").write_text(json.dumps(receipt))
                if key:
                    with self.assertRaises(ValueError):
                        sdk.verify_sdk(root)
                else:
                    self.assertEqual("PASS", sdk.verify_sdk(root)["status"])
            (root / "sdk-receipt.json").write_text(json.dumps(original))
            (root / "header.h").write_text("tampered")
            with self.assertRaises(ValueError):
                sdk.verify_sdk(root)

    def test_missing_sdk_rejected_before_build_directory_is_created(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                with self.assertRaisesRegex(ValueError, "verified pinned"):
                    build(root / "source", root / "output", sdk.TARGET, [], 2)
            self.assertFalse((root / "output").exists())

    def test_digest_is_checked_before_runtime_installation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "archive").write_bytes(b"wrong")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                sdk.install_runtime({"protobuf": root / "archive"}, root / "prefix")
            self.assertFalse((root / "prefix").exists())

    def test_zip_member_rejects_missing_duplicate_and_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with zipfile.ZipFile(root / "archive.zip", "w") as z:
                z.writestr("good.dll", b"verified")
                info = zipfile.ZipInfo("link.dll")
                info.external_attr = 0o120777 << 16
                z.writestr(info, "../../outside")
                z.writestr("duplicate.dll", "first")
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    z.writestr("duplicate.dll", "second")
            with zipfile.ZipFile(root / "archive.zip") as z:
                for member in ("missing.dll", "link.dll", "duplicate.dll"):
                    with self.assertRaises(ValueError):
                        sdk.copy_member(z, member, root / "bad")
                sdk.copy_member(z, "good.dll", root / "good")
                self.assertEqual(b"verified", (root / "good").read_bytes())
                with self.assertRaises(FileExistsError):
                    sdk.copy_member(z, "good.dll", root / "good")

    def test_every_provider_runtime_including_delay_load_must_be_bundled(self):
        bundled = set(sdk.runtime_files())
        audits = {"katago.exe": {"needed": ["onnxruntime.dll", "KERNEL32.dll"]},
                  "onnxruntime.dll": {"needed": ["DirectML.dll", "VCRUNTIME140.dll"]}}
        verify_runtime_closure(audits, bundled, bundled | {"katago.exe"})
        for missing in bundled:
            with self.subTest(missing=missing), self.assertRaisesRegex(ValueError, "runtime missing"):
                verify_runtime_closure(audits, bundled, bundled - {missing})

    def test_directml_libraries_are_not_globally_allowed_for_other_backends(self):
        header = "8664 machine (x64)"
        imports = " KERNEL32.dll\n onnxruntime.dll\n DirectML.dll\n VCRUNTIME140.dll\n d3d12.dll\n"
        inspect_pe(header, imports, set(sdk.runtime_files()), DIRECTML_SYSTEM_LIBRARIES)
        with self.assertRaises(ValueError):
            inspect_pe(header, imports)
        with self.assertRaises(ValueError):
            inspect_pe(header, imports + " rogue.dll\n", set(sdk.runtime_files()), DIRECTML_SYSTEM_LIBRARIES)


if __name__ == "__main__":
    unittest.main()
