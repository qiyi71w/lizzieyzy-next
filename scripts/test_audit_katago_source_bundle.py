import json
import unittest

from audit_katago_source_bundle import audit
from prepare_katago_source_assets import unpack
from test_stage_katago_source_release import SourceReleaseTest


class InstalledSourceTest(unittest.TestCase):
    def setUp(self):
        self.fixture = SourceReleaseTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.catalog = self.fixture.run_stage()
        self.target = "windows-cpu"
        self.engine = self.fixture.root / "installed"
        asset = self.catalog["assets"][self.target]
        unpack(self.fixture.root / "release" / asset["assetName"], self.engine, self.target, asset)

    def test_unchanged_installed_source_passes(self):
        audit(self.catalog, self.target, self.engine)

    def test_wrong_executable_is_rejected(self):
        (self.engine / "katago.exe").write_bytes(b"old executable")
        with self.assertRaisesRegex(ValueError, "missing or modified"):
            audit(self.catalog, self.target, self.engine)

    def test_dropped_license_is_rejected(self):
        (self.engine / "licenses/LICENSE").unlink()
        with self.assertRaisesRegex(ValueError, "missing or modified"):
            audit(self.catalog, self.target, self.engine)

    def test_different_backend_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "identity differs"):
            audit(self.catalog, "windows-opencl", self.engine)

    def test_duplicate_inventory_is_rejected(self):
        path = self.engine / "source-release.json"
        metadata = json.loads(path.read_text())
        metadata["files"].append(metadata["files"][0])
        path.write_text(json.dumps(metadata))
        with self.assertRaisesRegex(ValueError, "ambiguous"):
            audit(self.catalog, self.target, self.engine)

    def test_official_catalog_keeps_its_existing_audits(self):
        audit({"origin": "official-release"}, "windows-cpu", self.fixture.root / "absent")


if __name__ == "__main__":
    unittest.main()
