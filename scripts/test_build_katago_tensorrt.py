#!/usr/bin/env python3

import copy
import json
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_katago_tensorrt_dependencies as trt
from build_katago_macos_dependencies import digest, inventory
from build_katago_source import build
from prepare_bundled_nvidia_runtime import TENSORRT_10_9_URL, TENSORRT_10_9_SHA256, TENSORRT_10_9_SIZE_BYTES


class TensorRtSourceTest(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads(trt.LOCK_PATH.read_text(encoding="utf-8"))

    def archive(self, root, changes=None):
        files = {"include/NvInfer.h": b"header", "include/NvInferVersion.h":
                 b"#define NV_TENSORRT_MAJOR 10\n#define NV_TENSORRT_MINOR 9\n#define NV_TENSORRT_PATCH 0\n#define NV_TENSORRT_BUILD 34\n",
                 "lib/nvinfer_10.lib": b"link", "lib/nvonnxparser_10.lib": b"link",
                 "doc/Acknowledgements.txt": b"notices", "doc/Readme.txt": b"readme"}
        files.update({"lib/" + name: b"runtime" for name in self.lock["runtimeFiles"]})
        files.update(changes or {})
        archive = root / "trt.zip"
        with zipfile.ZipFile(archive, "w") as bundle:
            for name, data in files.items():
                if data is not None:
                    bundle.writestr("TensorRT-10.9.0.34/" + name, data)
        lock = copy.deepcopy(self.lock)
        lock["archive"].update(sizeBytes=archive.stat().st_size, sha256=digest(archive))
        return archive, lock

    def test_lock_matches_existing_distribution_without_runtime_upgrade(self):
        self.assertEqual(TENSORRT_10_9_URL, self.lock["archive"]["url"])
        self.assertEqual(TENSORRT_10_9_SHA256, self.lock["archive"]["sha256"])
        self.assertEqual(TENSORRT_10_9_SIZE_BYTES, self.lock["archive"]["sizeBytes"])
        self.assertEqual("12.8.0", self.lock["cudaVersion"])
        self.assertEqual(7, len(self.lock["runtimeFiles"]))
        self.assertEqual(len(trt.runtime_files()), len(set(trt.runtime_files())))

    def test_engine_requires_a_sealed_sdk(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                with self.assertRaisesRegex(ValueError, "verified pinned dependencies"):
                    build(root / "source", root / "build", trt.TARGET, [], 3)
            self.assertFalse((root / "build").exists())

    def test_configuration_uses_locked_headers_parser_and_cuda_without_cudnn_flags(self):
        prefix = Path("/tmp/space Chinese/sdk").resolve()
        options = trt.engine_options(prefix)
        self.assertTrue(any(option.endswith("nvinfer_10.lib") for option in options))
        self.assertTrue(any(option.endswith("nvonnxparser_10.lib") for option in options))
        self.assertTrue(any(option.startswith("-DCUDAToolkit_ROOT=") for option in options))
        self.assertFalse(any(option.startswith(("-DCUDNN_", "-DEIGEN3_INCLUDE_DIRS=")) for option in options))
        self.assertIn("-DProtobuf_USE_STATIC_LIBS=ON", options)
        self.assertIn(f"-DProtobuf_LIBRARY={prefix / 'lib/libprotobuf.lib'}", options)
        self.assertIn(f"-DProtobuf_PROTOC_EXECUTABLE={prefix / 'bin/protoc.exe'}", options)

    def test_protobuf_uses_the_audited_existing_source_and_static_runtime(self):
        directml = json.loads(trt.LOCK_PATH.with_name("katago_directml_dependencies.json").read_text())
        self.assertEqual(directml["dependencies"][0], self.lock["protobuf"])
        options = trt.protobuf_options(Path("sdk"))
        self.assertIn("-Dprotobuf_BUILD_SHARED_LIBS=OFF", options)
        self.assertIn("-Dprotobuf_MSVC_STATIC_RUNTIME=ON", options)
        self.assertIn("-Dprotobuf_BUILD_TESTS=OFF", options)

    def test_protobuf_is_verified_before_compilation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(trt.subprocess, "run") as run, \
                    patch.object(trt, "extract_verified", side_effect=ValueError("SHA mismatch")) as extract:
                with self.assertRaisesRegex(ValueError, "SHA mismatch"):
                    trt.install_protobuf(root, root / "sdk", self.lock, 3, io.StringIO())
                self.assertEqual(1, run.call_count)
                self.assertEqual(self.lock["protobuf"]["sha256"], extract.call_args.args[2])

    def test_protobuf_missing_build_output_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(trt.subprocess, "run") as run, \
                    patch.object(trt, "extract_verified", return_value=root / "source"), \
                    patch.object(trt.cuda.common, "environment", return_value={}):
                with self.assertRaisesRegex(ValueError, "SDK component missing"):
                    trt.install_protobuf(root, root / "sdk", self.lock, 3, io.StringIO())
                self.assertEqual(4, run.call_count)

    def test_install_retains_runtime_headers_and_notices(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, lock = self.archive(root)
            trt.install_archive(archive, root / "sdk", lock)
            self.assertEqual(set(self.lock["runtimeFiles"]), {p.name for p in (root / "sdk/runtime").iterdir()})
            self.assertEqual(b"header", (root / "sdk/tensorrt/include/NvInfer.h").read_bytes())
            self.assertEqual(b"notices", (root / "sdk/share/licenses/tensorrt/doc/Acknowledgements.txt").read_bytes())

    def test_size_and_digest_fail_before_writing(self):
        for field, value in (("sizeBytes", 1), ("sha256", "a" * 64)):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, lock = self.archive(root)
                lock["archive"][field] = value
                with self.assertRaisesRegex(ValueError, "size or SHA-256"):
                    trt.install_archive(archive, root / "sdk", lock)
                self.assertFalse((root / "sdk").exists())

    def test_missing_extra_and_escaping_files_are_rejected(self):
        for changes in ({"lib/nvinfer_10.dll": None}, {"lib/unexpected.dll": b"bad"},
                        {"../../escaped": b"bad"}, {"doc/Acknowledgements.txt": None},
                        {"lib/nvonnxparser_10.lib": None}):
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, lock = self.archive(root, changes)
                with self.assertRaises(ValueError):
                    trt.install_archive(archive, root / "sdk", lock)
                self.assertFalse((root / "sdk").exists())

    def test_colliding_runtime_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, lock = self.archive(root)
            target = root / "sdk/runtime/nvinfer_10.dll"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"keep")
            with self.assertRaisesRegex(ValueError, "overwrite"):
                trt.install_archive(archive, root / "sdk", lock)
            self.assertEqual(b"keep", target.read_bytes())

    def test_header_version_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, lock = self.archive(root)
            lock["version"] = "10.16.1.0"
            with self.assertRaisesRegex(ValueError, "version differs"):
                trt.install_archive(archive, root / "sdk", lock)

    def test_sdk_tampering_and_changed_cuda_lock_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "header.h").write_text("original")
            receipt = dict(schemaVersion=1, status="PASS", provider="tensorrt", system="Windows", arch="x86_64",
                           lockSha256=digest(trt.LOCK_PATH), cudaLockSha256=digest(trt.cuda.LOCK_PATH),
                           commonLockSha256=digest(trt.cuda.common.LOCK_PATH), configuration=trt.engine_options(root),
                           files=inventory(root))
            (root / "sdk-receipt.json").write_text(json.dumps(receipt))
            self.assertEqual("PASS", trt.verify_sdk(root)["status"])
            with patch("build_katago_tensorrt_dependencies.digest", return_value="different"):
                with self.assertRaisesRegex(ValueError, "receipt or files"):
                    trt.verify_sdk(root)
            (root / "header.h").write_text("tampered")
            with self.assertRaisesRegex(ValueError, "receipt or files"):
                trt.verify_sdk(root)


if __name__ == "__main__":
    unittest.main()
