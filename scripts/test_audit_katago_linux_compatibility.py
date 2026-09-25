import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from audit_katago_linux_compatibility import (
    LOCK_PATH, SOURCE_COMMIT, compare_symbols, digest, extract_baseline, symbol_versions, verify_version,
    write_probe_config,
)


class LinuxCompatibilityTest(unittest.TestCase):
    BASELINE = "GLIBC_2.3 GLIBC_2.34 GLIBCXX_3.4.9 GLIBCXX_3.4.30 CXXABI_1.3.13"

    def test_gtp_fixture_defines_required_logging_and_rule_keys(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "gtp.cfg"
            write_probe_config(path)
            values = dict(line.split(" = ") for line in path.read_text(encoding="utf-8").splitlines())
            self.assertEqual("false", values["logAllGTPCommunication"])
            self.assertEqual("false", values["logSearchInfo"])
            self.assertEqual("false", values["ponderingEnabled"])
            self.assertEqual("chinese", values["rules"])
            self.assertEqual("4", values["maxVisits"])

    def test_symbols_compare_numerically_not_lexicographically(self):
        self.assertEqual((3, 4, 30), symbol_versions(self.BASELINE)["GLIBCXX"])
        result = compare_symbols(self.BASELINE, {"katago": self.BASELINE})
        self.assertEqual("2.34", result["baseline"]["GLIBC"])

    def test_any_binary_raising_any_abi_floor_is_rejected(self):
        for symbol in ("GLIBC_2.35", "GLIBCXX_3.4.31", "CXXABI_1.3.14"):
            with self.subTest(symbol=symbol), self.assertRaisesRegex(ValueError, "raises"):
                compare_symbols(self.BASELINE, {"katago": self.BASELINE, "libOpenCL.so.1": symbol})

    def test_missing_baseline_namespaces_and_empty_candidates_are_rejected(self):
        with self.assertRaises(ValueError):
            compare_symbols("GLIBC_2.34", {"katago": self.BASELINE})
        with self.assertRaises(ValueError):
            compare_symbols(self.BASELINE, {})

    def test_libraries_without_cpp_symbols_do_not_raise_floor(self):
        result = compare_symbols(self.BASELINE, {"katago": self.BASELINE, "libz.so.1": "GLIBC_2.2.5"})
        self.assertEqual("", result["candidates"]["libz.so.1"]["GLIBCXX"])

    def test_source_version_requires_exact_revision(self):
        verify_version(f"KataGo v1.18.2\nGit revision: {SOURCE_COMMIT}\n", True)
        for text in ("KataGo v1.18.1", "KataGo v1.18.2\nGit revision: old"):
            with self.assertRaises(ValueError):
                verify_version(text, True)
        verify_version("KataGo v1.18.1", False)
        with self.assertRaises(ValueError):
            verify_version("KataGo v1.18.2", False)

    def test_only_hash_verified_baseline_executable_is_extracted(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = root / "official.zip"
            with zipfile.ZipFile(archive, "w") as zipped:
                zipped.writestr("katago", "fixture")
                zipped.writestr("default_gtp.cfg", "unneeded")
            expected = dict(sizeBytes=archive.stat().st_size, sha256=digest(archive))
            with patch.object(Path, "chmod", autospec=True, side_effect=Path.chmod) as chmod:
                extract_baseline(archive, root / "baseline", expected)
            chmod.assert_called_once_with(root / "baseline/katago", 0o755)
            self.assertEqual(["katago"], [path.name for path in (root / "baseline").iterdir()])
            if os.name != "nt":
                self.assertTrue((root / "baseline/katago").stat().st_mode & 0o111)
            for change in ({"sizeBytes": 1}, {"sha256": "0" * 64}):
                with self.assertRaisesRegex(ValueError, "mismatch"):
                    extract_baseline(archive, root / "bad", dict(expected, **change))
                self.assertFalse((root / "bad").exists())

    def test_baseline_archive_rejects_traversal_and_case_collision(self):
        for bad in ("../escape", "KATAGO"):
            with tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                archive = root / "official.zip"
                with zipfile.ZipFile(archive, "w") as zipped:
                    zipped.writestr("katago", "fixture")
                    zipped.writestr(bad, "fixture")
                with self.assertRaises(ValueError):
                    extract_baseline(archive, root / "out", dict(sizeBytes=archive.stat().st_size, sha256=digest(archive)))
                self.assertFalse((root / "out").exists())

    def test_lock_keeps_all_three_baselines_and_native_ubuntu_images_pinned(self):
        lock = json.loads(LOCK_PATH.read_text(encoding="utf-8"))
        self.assertEqual({"linux-cpu", "linux-opencl", "linux-nvidia"}, set(lock["baselines"]))
        self.assertEqual(2, len(lock["distributions"]))
        for image in lock["distributions"]:
            self.assertRegex(image["image"], r"^ubuntu@sha256:[0-9a-f]{64}$")
        for baseline in lock["baselines"].values():
            self.assertGreater(baseline["sizeBytes"], 0)
            self.assertRegex(baseline["sha256"], r"^[0-9a-f]{64}$")


if __name__ == "__main__":
    unittest.main()
