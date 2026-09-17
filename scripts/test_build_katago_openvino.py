#!/usr/bin/env python3

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import build_katago_openvino_dependencies as sdk
from build_katago_macos_dependencies import digest, inventory
from build_katago_source import build
from package_katago_source_windows import (
    OPENVINO_SYSTEM_LIBRARIES, inspect_pe, verify_runtime_closure,
)


class OpenVinoSourceTest(unittest.TestCase):
    def test_existing_runtime_and_matching_headers_are_pinned(self):
        lock = json.loads(sdk.LOCK_PATH.read_text())
        self.assertEqual("openvino", lock["provider"])
        self.assertEqual("7e76a52398ebf966bcbe4a10e552f438059edfce", lock["onnxruntimeSourceCommit"])
        dependencies = {item["name"]: item for item in lock["dependencies"]}
        self.assertEqual("2026.2.1", dependencies["openvino"]["version"])
        self.assertEqual("v1.18.1", dependencies["baseline-runtime"]["version"])
        self.assertEqual(13, len(dependencies))
        for name, item in dependencies.items():
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
            self.assertTrue(item["url"].startswith("https://"))
            if name.endswith(".h") or name in {"LICENSE", "ThirdPartyNotices.txt"}:
                self.assertEqual(lock["onnxruntimeSourceCommit"], item["version"])
                self.assertIn(item["version"], item["url"])

    def test_runtime_is_exact_and_does_not_copy_an_old_engine_or_unrelated_libraries(self):
        files = sdk.runtime_files()
        self.assertEqual(31, len(set(files)))
        self.assertEqual(len(files), len(set(files)))
        self.assertIn("onnxruntime_providers_openvino.dll", files)
        self.assertIn("openvino_intel_gpu_plugin.dll", files)
        self.assertIn("OpenCL.dll", files)
        self.assertIn("openvino_intel_npu_plugin.dll", files)
        for unrelated in ("katago.exe", "DirectML.dll", "libprotobuf.dll", "libcrypto-3-x64.dll", "z.dll", "zip.dll"):
            self.assertNotIn(unrelated, files)

    def test_import_library_requires_real_export_evidence(self):
        exports = "\n".join(f" {i+1} {i:X} 00012A00 {name}" for i, name in enumerate(sdk.IMPORT_SYMBOLS))
        definition = sdk.import_definition(exports)
        self.assertTrue(definition.startswith("LIBRARY onnxruntime.dll\nEXPORTS\n"))
        for symbol in sdk.IMPORT_SYMBOLS:
            self.assertIn(symbol, definition)
            with self.assertRaisesRegex(ValueError, "export missing"):
                sdk.import_definition(exports.replace(symbol, symbol + "Invalid"))
        with self.assertRaises(ValueError):
            sdk.import_definition("OrtGetApiBase appears in a diagnostic, not an export table")

    def test_provider_receipt_rejects_wrong_provider_hash_configuration_and_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "header.h").write_text("verified")
            original = {"schemaVersion": 1, "status": "PASS", "system": "Windows", "arch": "x86_64",
                        "provider": "openvino", "lockSha256": digest(sdk.LOCK_PATH),
                        "commonLockSha256": digest(sdk.onnx.common.LOCK_PATH),
                        "configuration": sdk.engine_options(root), "files": inventory(root)}
            for key, value in ((None, None), ("provider", "directml"), ("status", "FAIL"),
                               ("lockSha256", "wrong"), ("commonLockSha256", "wrong"),
                               ("configuration", []), ("files", [])):
                receipt = dict(original)
                if key: receipt[key] = value
                (root / "sdk-receipt.json").write_text(json.dumps(receipt))
                if key:
                    with self.assertRaises(ValueError): sdk.verify_sdk(root)
                else:
                    self.assertEqual("PASS", sdk.verify_sdk(root)["status"])

    def test_required_sdk_rejected_before_any_output_is_created(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                with self.assertRaisesRegex(ValueError, "verified pinned"):
                    build(root / "source", root / "output", sdk.TARGET, [], 2)
            self.assertFalse((root / "output").exists())

    def test_hash_failure_prevents_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "archive").write_bytes(b"tampered")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                sdk.stage_runtime({"protobuf": root / "archive"}, root / "prefix")
            self.assertFalse((root / "prefix").exists())

    def test_all_plugins_including_delayed_load_dependencies_are_required(self):
        files = set(sdk.runtime_files())
        verify_runtime_closure({}, files, files)
        for name in files:
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_runtime_closure({}, files, files - {name})

    def test_system_dependencies_are_scoped_to_openvino(self):
        headers = "8664 machine (x64)"
        dependencies = " KERNEL32.dll\n onnxruntime.dll\n openvino.dll\n tbb12.dll\n dxgi.dll\n"
        inspect_pe(headers, dependencies, set(sdk.runtime_files()), OPENVINO_SYSTEM_LIBRARIES)
        with self.assertRaises(ValueError): inspect_pe(headers, dependencies)
        with self.assertRaises(ValueError):
            inspect_pe(headers, dependencies + " DirectML.dll\n", set(sdk.runtime_files()), OPENVINO_SYSTEM_LIBRARIES)


if __name__ == "__main__":
    unittest.main()
