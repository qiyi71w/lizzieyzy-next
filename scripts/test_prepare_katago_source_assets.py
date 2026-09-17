import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import test_stage_katago_source_release as fixtures
from katago_asset_catalog import DEFAULT_CATALOG, validate_catalog
from prepare_katago_source_assets import DESTINATIONS, download, prepare, unpack


class PrepareSourceAssetsTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.SourceReleaseTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.catalog = self.fixture.run_stage()
        self.root = self.fixture.root
        self.catalog_path = self.root / "release/katago-assets.json"
        self.engines = self.root / "engines"

    def archive(self, target):
        return self.root / "release" / self.catalog["assets"][target]["assetName"]

    def prepare(self, targets):
        with mock.patch("prepare_katago_source_assets.download", side_effect=lambda c, t, p: self.archive(t)):
            prepare(self.catalog_path, targets, self.root / "cache", self.engines)

    def test_prepares_exact_configurations_licenses_and_source_identity(self):
        self.prepare(["windows-cpu", "macos-arm64"])
        self.assertEqual("gtp test config", (self.engines / "configs/gtp.cfg").read_text())
        self.assertEqual("analysis test config", (self.engines / "configs/analysis.cfg").read_text())
        self.assertTrue((self.engines / "macos-arm64/licenses/LICENSE").is_file())
        manifest = (self.engines / "windows-x64/lizzieyzy-next-katago-engine-manifest.txt").read_text()
        self.assertIn("Origin: project-source-build", manifest)
        self.assertIn(self.catalog["katagoSourceCommit"], manifest)

    def test_one_invalid_archive_leaves_existing_build_engines_untouched(self):
        (self.engines / "windows-x64").mkdir(parents=True)
        original = self.engines / "windows-x64/katago.exe"
        original.write_bytes(b"prior build")
        self.archive("windows-opencl").write_bytes(b"corrupt")
        with self.assertRaisesRegex(ValueError, "untrusted source archive"):
            self.prepare(["windows-cpu", "windows-opencl"])
        self.assertEqual(b"prior build", original.read_bytes())
        self.assertFalse((self.engines / "configs").exists())

    def test_experimental_rocm_family_marker_matches_existing_installer(self):
        self.prepare(["windows-rocm-gfx120x"])
        self.assertEqual("rocm-gfx120x\n", (self.engines / DESTINATIONS["windows-rocm-gfx120x"]
                         / "lizzieyzy-next-engine-backend.txt").read_text())

    def test_corrupted_cache_is_not_accepted_and_verified_cache_needs_no_network(self):
        with mock.patch("prepare_katago_source_assets.subprocess.run") as run:
            path = download(self.catalog, "windows-cpu", self.root / "release")
            self.assertEqual(self.archive("windows-cpu"), path)
            run.assert_not_called()

    def test_official_catalog_and_duplicate_targets_cannot_enter_source_path(self):
        official = json.loads(DEFAULT_CATALOG.read_text())
        official["origin"] = "official-release"
        official.pop("engineReleaseRepository", None)
        official.pop("engineReleaseTag", None)
        for target, asset in official["assets"].items():
            asset["assetName"] = f"katago-{official['katagoReleaseTag']}-{target}.zip"
        validate_catalog(official)
        official_path = self.root / "official.json"
        official_path.write_text(json.dumps(official))
        with mock.patch("prepare_katago_source_assets.download") as fetch:
            with self.assertRaisesRegex(ValueError, "reviewed source catalog"):
                prepare(official_path, ["windows-cpu"], self.root / "cache", self.engines)
            fetch.assert_not_called()
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.prepare(["windows-cpu", "windows-cpu"])

    def test_archive_cannot_be_used_for_another_backend(self):
        with self.assertRaisesRegex(ValueError, "different source or target"):
            unpack(self.archive("windows-cpu"), self.root / "wrong", "windows-opencl",
                   self.catalog["assets"]["windows-cpu"])

    def test_trt_destination_matches_existing_runtime_packager(self):
        self.assertEqual("windows-x64-nvidia-tensorrt", DESTINATIONS["windows-tensorrt"])


if __name__ == "__main__":
    unittest.main()
