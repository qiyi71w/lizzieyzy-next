#!/usr/bin/env python3

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import package_katago_source_macos as package
from build_katago_source import SOURCE_COMMIT, file_record


class SourcePackageTest(unittest.TestCase):
    def fixture(self, root):
        build, sdk = root / "build", root / "sdk"
        build.mkdir()
        sdk.mkdir()
        (build / "katago").write_bytes(b"compiled")
        (sdk / "sdk-receipt.json").write_text("{}")
        receipt = {
            "target": "macos-arm64", "buildStatus": "PASS", "sourceCommit": SOURCE_COMMIT,
            "backend": "METAL", "origin": "project-source-build", "dependencyLockSha256": "locked",
            "sdkReceipt": file_record(sdk / "sdk-receipt.json", sdk),
            "executable": file_record(build / "katago", build),
        }
        (build / "source-build.json").write_text(json.dumps(receipt))
        return build, sdk, receipt

    def test_verified_build_can_be_packaged(self):
        with tempfile.TemporaryDirectory() as temp:
            build, sdk, receipt = self.fixture(Path(temp))
            with patch.object(package, "verify_sdk", return_value={"lockSha256": "locked"}):
                self.assertEqual(receipt, package.validate_build(build, sdk))

    def test_modified_binary_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            build, sdk, _ = self.fixture(Path(temp))
            (build / "katago").write_bytes(b"other version")
            with patch.object(package, "verify_sdk", return_value={"lockSha256": "locked"}):
                with self.assertRaisesRegex(ValueError, "changed after compilation"):
                    package.validate_build(build, sdk)

    def test_wrong_source_and_backend_are_rejected(self):
        for field, value in (("sourceCommit", "old"), ("buildStatus", "FAIL"),
                             ("backend", "EIGEN"), ("target", "linux-cpu"),
                             ("origin", "official-release")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temp:
                build, sdk, receipt = self.fixture(Path(temp))
                receipt[field] = value
                (build / "source-build.json").write_text(json.dumps(receipt))
                with self.assertRaisesRegex(ValueError, "verified pinned"):
                    package.validate_build(build, sdk)

    def test_different_sdk_receipt_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            build, sdk, _ = self.fixture(Path(temp))
            (sdk / "sdk-receipt.json").write_text('{"another":"sdk"}')
            with patch.object(package, "verify_sdk", return_value={"lockSha256": "locked"}):
                with self.assertRaisesRegex(ValueError, "different SDK"):
                    package.validate_build(build, sdk)

    def test_existing_package_is_not_reused_or_deleted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            build, sdk, _ = self.fixture(root)
            output = root / "package"
            output.mkdir()
            (output / "keep").write_text("previous build")
            with self.assertRaisesRegex(ValueError, "fresh directory"):
                package.package(build, sdk, output)
            self.assertEqual("previous build", (output / "keep").read_text())

    def test_output_cannot_corrupt_sdk_or_build(self):
        with tempfile.TemporaryDirectory() as temp:
            build, sdk, _ = self.fixture(Path(temp))
            for output in (build / "out", sdk / "out"):
                with self.assertRaisesRegex(ValueError, "outside the build and SDK"):
                    package.package(build, sdk, output)


if __name__ == "__main__":
    unittest.main()
