#!/usr/bin/env python3
"""Focused tests for the pinned Linux CPU acceptance provisioner."""

from __future__ import annotations

import hashlib
import http.server
import importlib.util
import io
import json
import os
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path
from unittest import mock


SCRIPT_DIR = Path(__file__).resolve().parent
MODULE_PATH = SCRIPT_DIR / "prepare_cpu_engine_acceptance.py"
SPEC = importlib.util.spec_from_file_location("prepare_cpu_engine_acceptance", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {MODULE_PATH}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def archive_bytes(*, config: bool = True, unsafe_name: str | None = None) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        executable = zipfile.ZipInfo("katago/katago")
        executable.external_attr = (stat.S_IFREG | 0o755) << 16
        archive.writestr(executable, b"fixture-engine")
        if config:
            archive.writestr("katago/default_gtp.cfg", b"rules = chinese\n")
        if unsafe_name is not None:
            archive.writestr(unsafe_name, b"escape")
    return output.getvalue()


def catalog_for(archive: bytes, model: bytes) -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "katagoVersion": "1.18.1",
        "katagoReleaseTag": "v1.18.1",
        "katagoSourceCommit": "92ee95c0a4b25fec214da00951ab69e97e207729",
        "modelReleaseTag": "v1.17.1",
        "defaultModelId": "fixture-model",
        "models": {
            "fixture-model": {
                "fileName": "fixture.bin.gz",
                "downloadUrl": "https://example.invalid/fixture.bin.gz",
                "minimumKataGoVersion": "1.17.0",
                "sizeBytes": len(model),
                "sha256": sha256(model),
                "bundled": True,
            }
        },
        "assets": {
            "linux-cpu": {
                "platform": "linux-x64",
                "backend": "eigen",
                "assetName": "katago-v1.18.1-eigen-linux-x64.zip",
                "sizeBytes": len(archive),
                "sha256": sha256(archive),
                "releaseTier": "stable",
            }
        },
    }


class Response(io.BytesIO):
    def __enter__(self) -> "Response":
        return self

    def __exit__(self, *_args: object) -> None:
        self.close()


class CpuAcceptanceProvisionerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="cpu-acceptance-test.")
        self.root = Path(self.temp.name)
        self.archive = archive_bytes()
        self.model = b"fixture-model"
        self.catalog = catalog_for(self.archive, self.model)
        self.catalog_path = self.root / "katago-assets.json"
        self.catalog_path.write_text(json.dumps(self.catalog), encoding="utf-8")
        self.version_output = "KataGo v1.18.1\nUsing Eigen(CPU) backend\n"

    def tearDown(self) -> None:
        self.temp.cleanup()

    def prepare(self, **kwargs: object) -> Path:
        # These tests use a fake executable/version runner, not a real Linux binary.
        with mock.patch.object(MODULE, "supported_host", return_value=True):
            return MODULE.prepare(
                self.root / "acceptance",
                catalog_path=self.catalog_path,
                version_runner=lambda _path: self.version_output,
                **kwargs,
            )

    def test_unsupported_host_fails_before_output_creation(self) -> None:
        with mock.patch.object(MODULE, "supported_host", return_value=False):
            with self.assertRaisesRegex(MODULE.ProvisioningError, "requires Linux x64"):
                MODULE.prepare(self.root / "not-created", catalog_path=self.catalog_path)
        self.assertFalse((self.root / "not-created").exists())

    def seed_cache(self) -> Path:
        cache = self.root / "acceptance" / "cache"
        cache.mkdir(parents=True)
        (cache / self.catalog["assets"]["linux-cpu"]["assetName"]).write_bytes(self.archive)
        (cache / self.catalog["models"]["fixture-model"]["fileName"]).write_bytes(self.model)
        return cache

    def test_verified_cache_is_reused_without_network(self) -> None:
        self.seed_cache()

        manifest_path = self.prepare(opener=mock.Mock(side_effect=AssertionError("network used")))
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

        self.assertFalse(manifest["networkUsed"])
        self.assertEqual("eigen", manifest["katago"]["backend"])
        self.assertEqual(self.version_output.strip(), manifest["katago"]["executable"]["versionOutput"])
        for section, key in (("katago", "executable"),):
            self.assertTrue(Path(manifest[section][key]["path"]).is_file())
        self.assertTrue(Path(manifest["model"]["path"]).is_file())
        self.assertTrue(Path(manifest["config"]["path"]).is_file())

    def test_network_downloads_use_part_then_publish_verified_cache(self) -> None:
        payloads = [self.archive, self.model]
        replacements: list[tuple[Path, Path]] = []
        original_replace = os.replace

        def opener(_url: str) -> Response:
            return Response(payloads.pop(0))

        def recording_replace(source: os.PathLike[str], destination: os.PathLike[str]) -> None:
            replacements.append((Path(source), Path(destination)))
            original_replace(source, destination)

        with mock.patch.object(MODULE.os, "replace", side_effect=recording_replace):
            manifest_path = self.prepare(opener=opener)

        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        cache_publications = [pair for pair in replacements if pair[1].parent.name == "cache"]
        self.assertEqual(2, len(cache_publications))
        self.assertTrue(all(source.name.endswith(".part") for source, _ in cache_publications))
        self.assertTrue(manifest["networkUsed"])
        self.assertFalse(any((self.root / "acceptance" / "cache").glob("*.part")))

    def test_exact_size_and_hash_mismatch_are_rejected(self) -> None:
        cache = self.seed_cache()
        archive_name = self.catalog["assets"]["linux-cpu"]["assetName"]
        (cache / archive_name).write_bytes(self.archive + b"x")
        with self.assertRaisesRegex(MODULE.ProvisioningError, "size mismatch"):
            self.prepare(opener=mock.Mock(side_effect=OSError("offline")))

        (cache / archive_name).write_bytes(b"x" * len(self.archive))
        with self.assertRaisesRegex(MODULE.ProvisioningError, "SHA-256 mismatch"):
            self.prepare(opener=mock.Mock(side_effect=OSError("offline")))

    def test_oversized_http_body_stops_before_reading_or_writing_unbounded_data(self) -> None:
        class EndlessResponse(Response):
            read_count = 0

            def read1(self, size: int = -1) -> bytes:
                self.read_count += 1
                if self.read_count > 2:
                    raise AssertionError("reader continued after the pinned file size")
                return b"x" * size

        response = EndlessResponse()
        destination = self.root / "oversized.bin"
        with self.assertRaisesRegex(MODULE.ProvisioningError, "exceeds pinned size"):
            MODULE.download_verified(
                "https://example.invalid/oversized", destination, 8, sha256(b"x" * 8),
                "fixture", lambda _url: response,
            )
        self.assertEqual(1, response.read_count)
        self.assertFalse(destination.exists())
        self.assertFalse(destination.with_name(destination.name + ".part").exists())

    def test_stalled_and_slow_http_bodies_fail_without_publication(self) -> None:
        stop = threading.Event()
        requested = threading.Event()

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                requested.set()
                self.send_response(200)
                self.send_header("Content-Length", "8192")
                self.end_headers()
                self.wfile.flush()
                if self.path == "/stall":
                    stop.wait(5)
                else:
                    while not stop.wait(0.02):
                        try:
                            self.wfile.write(b"x")
                            self.wfile.flush()
                        except OSError:
                            break

            def log_message(self, *_args: object) -> None:
                pass

        probe = """
import json, sys
from pathlib import Path
from scripts import prepare_cpu_engine_acceptance as provisioner
# Exercise only HTTP failures with cached fixtures on every CI host.
provisioner.supported_host = lambda: True
provisioner.DOWNLOAD_TIMEOUT_SECONDS = 0.2
provisioner.DOWNLOAD_DEADLINE_SECONDS = 0.5
try:
    provisioner.prepare(Path(sys.argv[1]), catalog_path=Path(sys.argv[2]))
except provisioner.ProvisioningError:
    print(json.dumps({"failed": True}))
else:
    print(json.dumps({"failed": False}))
"""
        for mode in ("stall", "slow"):
            with self.subTest(mode=mode):
                stop.clear()
                requested.clear()
                server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
                thread = threading.Thread(target=server.serve_forever, daemon=True)
                thread.start()
                root = self.root / mode
                self.catalog["models"]["fixture-model"]["downloadUrl"] = (
                    f"http://127.0.0.1:{server.server_port}/{mode}"
                )
                self.catalog_path.write_text(json.dumps(self.catalog), encoding="utf-8")
                cache = root / "cache"
                cache.mkdir(parents=True)
                (cache / self.catalog["assets"]["linux-cpu"]["assetName"]).write_bytes(
                    self.archive
                )
                try:
                    result = subprocess.run(
                        [sys.executable, "-c", probe, str(root), str(self.catalog_path)],
                        cwd=SCRIPT_DIR.parent,
                        capture_output=True,
                        text=True,
                        timeout=4,
                    )
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertTrue(requested.is_set(), "download did not reach the HTTP peer")
                    self.assertTrue(json.loads(result.stdout)["failed"])
                    self.assertEqual({"cache"}, {entry.name for entry in root.iterdir()})
                    self.assertEqual(
                        {self.catalog["assets"]["linux-cpu"]["assetName"]},
                        {entry.name for entry in cache.iterdir()},
                    )
                finally:
                    stop.set()
                    server.shutdown()
                    server.server_close()
                    thread.join()

    def test_path_traversal_archive_is_rejected_before_extraction(self) -> None:
        self.archive = archive_bytes(unsafe_name="../escaped")
        self.catalog = catalog_for(self.archive, self.model)
        self.catalog_path.write_text(json.dumps(self.catalog), encoding="utf-8")
        self.seed_cache()

        with self.assertRaisesRegex(MODULE.ProvisioningError, "unsafe archive entry"):
            self.prepare()

        self.assertFalse((self.root / "acceptance" / "escaped").exists())

    def test_default_gtp_config_is_required(self) -> None:
        self.archive = archive_bytes(config=False)
        self.catalog = catalog_for(self.archive, self.model)
        self.catalog_path.write_text(json.dumps(self.catalog), encoding="utf-8")
        self.seed_cache()

        with self.assertRaisesRegex(MODULE.ProvisioningError, "default_gtp.cfg"):
            self.prepare()

    def test_manifest_is_last_atomic_publication(self) -> None:
        self.seed_cache()
        replacements: list[tuple[Path, Path]] = []
        original_replace = os.replace

        def recording_replace(source: os.PathLike[str], destination: os.PathLike[str]) -> None:
            replacements.append((Path(source), Path(destination)))
            original_replace(source, destination)

        with mock.patch.object(MODULE.os, "replace", side_effect=recording_replace):
            manifest_path = self.prepare()

        self.assertEqual(manifest_path, replacements[-1][1])
        self.assertEqual("manifest.json.part", replacements[-1][0].name)
        self.assertEqual("manifest.json", replacements[-1][1].name)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        self.assertEqual(1, manifest["schemaVersion"])
        self.assertEqual(sha256(self.catalog_path.read_bytes()), manifest["catalog"]["sha256"])


if __name__ == "__main__":
    unittest.main()
