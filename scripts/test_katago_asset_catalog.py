import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import katago_asset_catalog


class KataGoAssetCatalogTest(unittest.TestCase):
    def test_catalog_is_valid_and_b11_is_default(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        default_model = catalog["models"][catalog["defaultModelId"]]

        self.assertEqual("1.18.2", catalog["katagoVersion"])
        self.assertEqual("project-source-build", catalog["origin"])
        self.assertEqual("47aadc08518b3e121f22539796c911002f699584", catalog["katagoSourceCommit"])
        self.assertEqual(15, len(catalog["assets"]))
        self.assertEqual("kata1-tf3-b11c768-s11500M-d6163M.bin.gz", default_model["fileName"])
        self.assertEqual(211568937, default_model["sizeBytes"])
        self.assertTrue(default_model["bundled"])

    def test_cli_reads_a_scalar_path(self):
        completed = subprocess.run(
            [
                sys.executable,
                str(Path(katago_asset_catalog.__file__)),
                "get",
                "assets.windows-nvidia.runtimeProfile",
            ],
            check=True,
            capture_output=True,
            text=True,
        )

        self.assertEqual("cuda12.8-cudnn9", completed.stdout.strip())

    def test_model_urls_preserve_release_fallback(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        self.assertEqual(
            "https://media.katagotraining.org/uploaded/networks/models/kata1/"
            "kata1-tf3-b11c768-s11500M-d6163M.bin.gz",
            katago_asset_catalog.model_download_url(catalog, "b11-flagship"),
        )
        self.assertIn("/v1.17.1/", katago_asset_catalog.model_download_url(catalog, "b10-balanced"))

    def test_validation_rejects_untrusted_model_origin(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        catalog["models"]["b11-flagship"]["downloadUrl"] = "https://example.com/model.bin.gz"
        with self.assertRaisesRegex(ValueError, "unsupported official downloadUrl"):
            katago_asset_catalog.validate_catalog(catalog)

    def test_validation_rejects_unpinned_asset(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        catalog["assets"]["windows-cpu"]["sha256"] = "missing"
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "catalog.json"
            path.write_text(json.dumps(catalog), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "invalid sha256"):
                katago_asset_catalog.load_catalog(path)

    def test_validation_requires_unified_nvidia_executable_digest(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        del catalog["assets"]["windows-nvidia"]["executableSha256"]
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "catalog.json"
            path.write_text(json.dumps(catalog), encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "requires executableSha256"):
                katago_asset_catalog.load_catalog(path)

    def test_static_zlib_linkage_is_limited_to_pinned_project_windows_gpu_assets(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        self.assertEqual("static", catalog["assets"]["windows-nvidia"]["zlibLinkage"])
        self.assertEqual("static", catalog["assets"]["windows-tensorrt"]["zlibLinkage"])

        for asset_id, linkage in (("windows-nvidia", None), ("windows-cpu", "static")):
            with self.subTest(asset_id=asset_id, linkage=linkage):
                candidate = json.loads(json.dumps(catalog))
                if linkage is None:
                    candidate["assets"][asset_id].pop("zlibLinkage")
                else:
                    candidate["assets"][asset_id]["zlibLinkage"] = linkage
                with self.assertRaisesRegex(ValueError, "origin-aware zlibLinkage"):
                    katago_asset_catalog.validate_catalog(candidate)

    def source_catalog(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        catalog.update(origin="project-source-build", engineReleaseRepository="wimi321/lizzieyzy-next",
                       engineReleaseTag="next-2026-09-17.1")
        for asset_id, asset in catalog["assets"].items():
            asset["assetName"] = f"katago-source-{catalog['katagoSourceCommit'][:12]}-{asset_id}.zip"
        return catalog

    def test_source_downloads_use_project_but_models_stay_official(self):
        catalog = self.source_catalog()
        katago_asset_catalog.validate_catalog(catalog)
        self.assertTrue(katago_asset_catalog.asset_download_url(catalog, "windows-tensorrt").startswith(
            "https://github.com/wimi321/lizzieyzy-next/releases/download/next-2026-09-17.1/"))
        self.assertIn("lightvector/KataGo", katago_asset_catalog.model_download_url(catalog, "b10-balanced"))

    def test_source_downloads_reject_foreign_repository_mutable_tag_and_wrong_commit(self):
        for field, value in (("engineReleaseRepository", "evil/KataGo"), ("engineReleaseTag", "latest"),
                             ("engineReleaseTag", "next-2026-09-17.1/../other"),
                             ("katagoSourceCommit", "master"), ("origin", "unknown")):
            with self.subTest(field=field, value=value):
                catalog = self.source_catalog()
                catalog[field] = value
                with self.assertRaises(ValueError):
                    katago_asset_catalog.validate_catalog(catalog)

    def test_source_downloads_reject_wrong_asset_name(self):
        for name in ("../engine.zip", "old-engine.zip", "folder\\engine.zip"):
            catalog = self.source_catalog()
            catalog["assets"]["windows-cpu"]["assetName"] = name
            with self.assertRaises(ValueError):
                katago_asset_catalog.validate_catalog(catalog)

    def test_official_origin_cannot_override_repository(self):
        catalog = katago_asset_catalog.load_catalog(katago_asset_catalog.DEFAULT_CATALOG)
        catalog["origin"] = "official-release"
        catalog["engineReleaseRepository"] = "wimi321/lizzieyzy-next"
        with self.assertRaises(ValueError):
            katago_asset_catalog.validate_catalog(catalog)


if __name__ == "__main__":
    unittest.main()
