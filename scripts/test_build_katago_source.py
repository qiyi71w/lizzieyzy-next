#!/usr/bin/env python3

import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from build_katago_source import (
    SOURCE_COMMIT, TARGETS, build, check_host, check_release_receipts, check_source, configuration,
    file_record, macos_minimum_version,
)


class SourceBuildTest(unittest.TestCase):
    def receipts(self):
        return [
            {
                "schemaVersion": 1, "sourceRepository": "https://github.com/lightvector/KataGo",
                "target": target, "sourceCommit": SOURCE_COMMIT, "origin": "project-source-build",
                "backend": TARGETS[target][2],
                "executable": {"file": "katago.exe" if target.startswith("windows-") else "katago",
                               "sizeBytes": 1, "sha256": "a" * 64},
                "buildStatus": "PASS", "packagingStatus": "PASS", "dependencyAuditStatus": "PASS",
                "hardwareAcceptanceStatus": "PASS" if TARGETS[target][2] == "EIGEN" else "PENDING_HARDWARE",
                "hardwareAcceptanceReason": "CI runner has no corresponding GPU",
            }
            for target in TARGETS
        ]

    def test_exact_matrix(self):
        self.assertEqual(15, len(TARGETS))
        self.assertEqual(10, sum(key.startswith("windows-") for key in TARGETS))
        self.assertEqual(3, sum(key.startswith("linux-") for key in TARGETS))
        self.assertEqual(2, sum(key.startswith("macos-") for key in TARGETS))

    def test_file_record_uses_actual_size_and_digest(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "katago"
            path.write_bytes(b"abc")
            self.assertEqual({
                "file": "katago", "sizeBytes": 3,
                "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            }, file_record(path, Path(root)))

    def test_no_auto_fetch_or_disabled_revision(self):
        for target in TARGETS:
            options = configuration(target, [])
            self.assertIn("-DNO_GIT_REVISION=0", options)
            self.assertIn("-DKATAGO_AUTO_FETCH_DEPS=OFF", options)

    def test_sdk_options_cannot_override_identity(self):
        for setting in ("NO_GIT_REVISION=1", "USE_BACKEND=DUMMY", "CMAKE_BUILD_TYPE=Debug"):
            with self.subTest(setting=setting), self.assertRaises(ValueError):
                configuration("windows-cpu", [setting])

    def test_macos_deployment_target_does_not_follow_build_host(self):
        for target in ("macos-arm64", "macos-amd64"):
            self.assertIn("-DCMAKE_OSX_DEPLOYMENT_TARGET=15.0", configuration(target, []))
            self.assertIn(
                f"-DCMAKE_Swift_FLAGS=-target {TARGETS[target][1]}-apple-macosx15.0 "
                "-Xlinker -headerpad_max_install_names",
                configuration(target, []),
            )
            with self.assertRaises(ValueError):
                configuration(target, ["CMAKE_OSX_DEPLOYMENT_TARGET=26.0"])
        self.assertEqual("15.0", macos_minimum_version("    minos 15.0\n      sdk 26.5\n"))

    def test_macos_actual_load_commands_must_keep_compatibility(self):
        for value in ("minos 26.0\n", "minos 15.1\n", "sdk 26.5\n",
                      "minos 15.0\nminos 15.0\n"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                macos_minimum_version(value)

    def test_sdk_values_are_separate_arguments(self):
        value = "CMAKE_PREFIX_PATH=C:/Chinese path/sdk"
        self.assertIn("-D" + value, configuration("windows-cpu", [value]))

    def test_source_sha_is_mandatory(self):
        with patch("build_katago_source.checked", return_value="old"):
            with self.assertRaisesRegex(ValueError, "pinned"):
                check_source(Path("source"))

    def test_untracked_source_is_rejected(self):
        with patch("build_katago_source.checked", side_effect=[SOURCE_COMMIT, "?? cpp/overrides.h"]):
            with self.assertRaisesRegex(ValueError, "clean"):
                check_source(Path("source"))

    def test_wrong_os_cannot_claim_target(self):
        with patch("build_katago_source.platform.system", return_value="Darwin"):
            with self.assertRaises(ValueError):
                check_host("windows-cpu")

    def test_failed_build_cannot_reuse_stale_output(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)
            output = path / "build"
            output.mkdir()
            stale = output / "katago"
            stale.write_bytes(b"stale")
            with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                with self.assertRaises(FileExistsError):
                    build(path / "source", output, "linux-cpu", [], 2)
            self.assertEqual(b"stale", stale.read_bytes())

    def test_build_failure_records_failure_not_approval(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)
            with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                with patch("build_katago_source.subprocess.run", side_effect=RuntimeError("compile failed")):
                    with self.assertRaises(RuntimeError):
                        build(path / "source", path / "build", "linux-cpu", [], 2)
            text = (path / "build/source-build.json").read_text()
            self.assertIn('"buildStatus": "FAIL"', text)
            self.assertIn('"packagingStatus": "NOT_RUN"', text)

    def test_macos_cannot_use_unverified_host_dependencies(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)
            with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                with self.assertRaisesRegex(ValueError, "verified pinned dependencies"):
                    build(path / "source", path / "build", "macos-arm64", [], 2)
            self.assertFalse((path / "build").exists())

    def test_missing_gpu_hardware_is_distinct_from_build_failure(self):
        check_release_receipts(self.receipts())

    def test_cpu_must_run_not_claim_missing_gpu(self):
        receipts = self.receipts()
        receipts[0]["hardwareAcceptanceStatus"] = "PENDING_HARDWARE"
        with self.assertRaisesRegex(ValueError, "CPU execution"):
            check_release_receipts(receipts)

    def test_pending_gpu_needs_reason(self):
        receipts = self.receipts()
        receipts[1]["hardwareAcceptanceReason"] = " "
        with self.assertRaisesRegex(ValueError, "explicit reason"):
            check_release_receipts(receipts)

    def test_binary_identity_and_backend_are_mandatory(self):
        for field, value in (("file", "../katago.exe"), ("sizeBytes", 0),
                             ("sizeBytes", True), ("sha256", "invalid")):
            receipts = self.receipts()
            receipts[0]["executable"][field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "executable identity"):
                check_release_receipts(receipts)
        receipts = self.receipts()
        receipts[0]["backend"] = "CUDA"
        with self.assertRaisesRegex(ValueError, "source identity"):
            check_release_receipts(receipts)

    def test_missing_target_rejects_release(self):
        with self.assertRaisesRegex(ValueError, "missing build targets"):
            check_release_receipts(self.receipts()[:-1])

    def test_duplicate_target_rejects_release(self):
        receipts = self.receipts()
        with self.assertRaisesRegex(ValueError, "duplicate"):
            check_release_receipts(receipts + [receipts[0]])

    def test_actual_hardware_failure_cannot_be_relabelled_by_other_gates(self):
        receipts = self.receipts()
        receipts[0]["hardwareAcceptanceStatus"] = "FAIL"
        with self.assertRaisesRegex(ValueError, "actual failure"):
            check_release_receipts(receipts)

    def test_every_non_hardware_gate_is_required(self):
        for field in ("buildStatus", "packagingStatus", "dependencyAuditStatus"):
            receipts = self.receipts()
            receipts[0][field] = "NOT_RUN"
            with self.subTest(field=field), self.assertRaises(ValueError):
                check_release_receipts(receipts)

    def test_official_old_binary_cannot_fill_missing_self_build(self):
        for field, value in (("sourceCommit", "old"), ("origin", "official-release")):
            receipts = copy.deepcopy(self.receipts())
            receipts[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                check_release_receipts(receipts)


if __name__ == "__main__":
    unittest.main()
