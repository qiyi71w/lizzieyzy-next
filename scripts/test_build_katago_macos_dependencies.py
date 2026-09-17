#!/usr/bin/env python3
"""Offline integrity and isolation tests for macOS source dependencies."""

import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import build_katago_macos_dependencies as sdk


class MacosSdkTest(unittest.TestCase):
    def test_lock_has_five_pinned_https_sources(self):
        lock = json.loads(sdk.LOCK_PATH.read_text())
        self.assertEqual("15.0", lock["minimumMacOSVersion"])
        self.assertEqual(["abseil", "protobuf", "xz", "zstd", "libzip"],
                         [item["name"] for item in lock["dependencies"]])
        for item in lock["dependencies"]:
            self.assertTrue(item["url"].startswith("https://"))
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
            self.assertNotIn("latest", item["url"])

    def test_host_environment_is_not_inherited_as_sdk_configuration(self):
        with patch.dict(os.environ, {"CMAKE_PREFIX_PATH": "/bad", "CPATH": "/bad",
                                    "LDFLAGS": "-L/bad", "PKG_CONFIG_PATH": "/bad"}):
            env = sdk.sdk_environment(Path("/isolated"))
        self.assertNotIn("CMAKE_PREFIX_PATH", env)
        self.assertNotIn("CPATH", env)
        self.assertNotIn("LDFLAGS", env)
        self.assertNotIn("PKG_CONFIG_PATH", env)
        self.assertEqual(str(Path("/isolated/lib/pkgconfig")), env["PKG_CONFIG_LIBDIR"])
        self.assertEqual("15.0", env["MACOSX_DEPLOYMENT_TARGET"])

    def test_sdk_configuration_locks_arch_and_os(self):
        for arch in ("arm64", "x86_64"):
            options = sdk.cmake_options(Path("/isolated"), arch)
            self.assertIn(f"-DCMAKE_OSX_ARCHITECTURES={arch}", options)
            self.assertIn("-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0", options)
            self.assertIn("-DCMAKE_IGNORE_PREFIX_PATH=/opt/homebrew;/usr/local", options)

    def archive(self, root, member):
        path = root / "source.tar"
        with tarfile.open(path, "w") as archive:
            item = tarfile.TarInfo(member)
            item.size = 3
            archive.addfile(item, io.BytesIO(b"abc"))
        return path

    def test_corrupt_archive_never_extracts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = self.archive(root, "source/file")
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                sdk.extract_verified(archive, root / "out", "0" * 64)
            self.assertFalse((root / "out").exists())

    def test_valid_archive_extracts(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = self.archive(root, "source/file")
            source = sdk.extract_verified(archive, root / "out", sdk.digest(archive))
            self.assertEqual(b"abc", (source / "file").read_bytes())

    def test_archive_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = self.archive(root, "../escape")
            with self.assertRaises(tarfile.FilterError):
                sdk.extract_verified(archive, root / "out", sdk.digest(archive))
            self.assertFalse((root / "escape").exists())

    def test_receipt_rejects_changed_or_added_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "lib").mkdir()
            (root / "lib/library").write_bytes(b"abc")
            receipt = {"status": "PASS", "arch": "arm64", "lockSha256": sdk.digest(sdk.LOCK_PATH),
                       "minimumMacOSVersion": "15.0", "files": sdk.inventory(root),
                       "configuration": sdk.cmake_options(root.resolve(), "arm64")}
            (root / "sdk-receipt.json").write_text(json.dumps(receipt))
            sdk.verify_sdk(root, "arm64")
            with self.assertRaisesRegex(ValueError, "locked build"):
                sdk.verify_sdk(root, "x86_64")
            (root / "lib/extra").write_bytes(b"abc")
            with self.assertRaisesRegex(ValueError, "changed"):
                sdk.verify_sdk(root, "arm64")
            (root / "lib/extra").unlink()
            (root / "lib/library").write_bytes(b"def")
            with self.assertRaisesRegex(ValueError, "changed"):
                sdk.verify_sdk(root, "arm64")

    def test_failed_or_stale_lock_receipt_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            for field, value in (("status", "FAIL"), ("lockSha256", "0" * 64),
                                 ("minimumMacOSVersion", "26.0")):
                receipt = {"status": "PASS", "arch": "arm64", "lockSha256": sdk.digest(sdk.LOCK_PATH),
                           "minimumMacOSVersion": "15.0", field: value}
                (root / "sdk-receipt.json").write_text(json.dumps(receipt))
                with self.subTest(field=field), self.assertRaisesRegex(ValueError, "locked build"):
                    sdk.verify_sdk(root, "arm64")


if __name__ == "__main__":
    unittest.main()
