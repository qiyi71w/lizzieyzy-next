#!/usr/bin/env python3

import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import build_katago_linux_cuda_dependencies as cuda
from build_katago_macos_dependencies import digest, inventory
from build_katago_source import build
from package_katago_source_linux_cuda import VENDOR_LIBRARIES, audit_binary, external_runtime


class LinuxCudaSourceTest(unittest.TestCase):
    def archive(self, root, entries):
        path = root / "input.tar"
        with tarfile.open(path, "w") as bundle:
            for name, value in entries.items():
                member = tarfile.TarInfo("component/" + name)
                if isinstance(value, tuple):
                    member.type = tarfile.SYMTYPE
                    member.linkname = value[0]
                    bundle.addfile(member)
                else:
                    member.size = len(value)
                    member.mode = 0o755
                    bundle.addfile(member, io.BytesIO(value))
        return path, {"name": "test", "destination": "cuda", "sizeBytes": path.stat().st_size,
                      "sha256": digest(path)}

    def test_dependency_versions_match_current_linux_runtime(self):
        lock = json.loads(cuda.LOCK_PATH.read_text())
        self.assertEqual("12.1.1", lock["cudaVersion"])
        self.assertEqual("12.1.105", lock["nvccVersion"])
        self.assertEqual("9.8.0.87", lock["cudnnVersion"])
        self.assertEqual("external", lock["runtimeMode"])
        self.assertEqual(7, len(lock["dependencies"]))
        for item in lock["dependencies"]:
            self.assertTrue(item["url"].startswith("https://developer.download.nvidia.com/"))
            self.assertIn("linux-x86_64", item["url"])
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")
            self.assertGreater(item["sizeBytes"], 0)

    def test_no_host_cuda_or_injected_flags(self):
        with patch.dict(os.environ, {"CUDA_HOME": "/host", "CUDA_PATH_V12_8": "/host",
                                     "CUDAFLAGS": "-arch=native", "CUDACXX": "/wrong",
                                     "NVCC_APPEND_FLAGS": "--allow-unsupported-compiler",
                                     "LD_LIBRARY_PATH": "/untrusted", "LD_PRELOAD": "bad"}):
            env = cuda.environment(Path("/tmp/sdk"))
        for key in ("CUDA_HOME", "CUDA_PATH_V12_8", "CUDAFLAGS", "CUDACXX", "NVCC_APPEND_FLAGS", "LD_PRELOAD"):
            self.assertNotIn(key, env)
        self.assertNotIn("untrusted", env["LD_LIBRARY_PATH"])
        self.assertIn("cudnn", env["LD_LIBRARY_PATH"])

    def test_configuration_keeps_upstream_architectures_and_locked_sdk(self):
        options = cuda.engine_options(Path("/tmp/Chinese space/sdk"))
        self.assertFalse(any("CMAKE_CUDA_ARCHITECTURES=" in option for option in options))
        self.assertFalse(any("EIGEN3_INCLUDE_DIRS=" in option for option in options))
        self.assertTrue(any(option.endswith(str(Path("cuda/bin/nvcc"))) for option in options))
        self.assertTrue(any(option.endswith(str(Path("cudnn/lib/libcudnn.so"))) for option in options))
        self.assertIn("-DCMAKE_INSTALL_RPATH=$ORIGIN", options)

    def test_linux_cuda_requires_verified_sdk_before_any_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                with self.assertRaisesRegex(ValueError, "verified pinned dependencies"):
                    build(root / "source", root / "build", "linux-nvidia", [], 2)
            self.assertFalse((root / "build").exists())

    def test_install_preserves_executable_and_license(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, item = self.archive(root, {"bin/nvcc": b"compiler", "LICENSE": b"license"})
            cuda.install_archive(archive, root / "sdk", item, root / "extract")
            self.assertEqual(b"compiler", (root / "sdk/cuda/bin/nvcc").read_bytes())
            self.assertEqual(b"license", (root / "sdk/share/licenses/test/LICENSE").read_bytes())

    def test_size_and_sha_rejected_before_extraction(self):
        for field, bad in (("sizeBytes", 1), ("sha256", "a" * 64)):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, item = self.archive(root, {"bin/nvcc": b"compiler", "LICENSE": b"license"})
                item[field] = bad
                with self.assertRaises(ValueError):
                    cuda.install_archive(archive, root / "sdk", item, root / "extract")
                self.assertFalse((root / "sdk").exists())

    def test_colliding_dependency_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, item = self.archive(root, {"bin/nvcc": b"compiler", "LICENSE": b"license"})
            target = root / "sdk/cuda/bin/nvcc"
            target.parent.mkdir(parents=True)
            target.write_bytes(b"original")
            with self.assertRaisesRegex(ValueError, "overwrite"):
                cuda.install_archive(archive, root / "sdk", item, root / "extract")
            self.assertEqual(b"original", target.read_bytes())

    def test_license_is_required(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, item = self.archive(root, {"bin/nvcc": b"compiler"})
            with self.assertRaisesRegex(ValueError, "notice missing"):
                cuda.install_archive(archive, root / "sdk", item, root / "extract")

    def test_traversal_and_escaping_symlink_rejected(self):
        for path, data in (("../../outside", b"bad"), ("lib/bad", ("../../../outside",))):
            with self.subTest(path=path), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, item = self.archive(root, {path: data, "LICENSE": b"license"})
                with self.assertRaises((ValueError, tarfile.FilterError)):
                    cuda.install_archive(archive, root / "sdk", item, root / "extract")

    @unittest.skipIf(os.name == "nt", "Linux SONAME link storage is tested on POSIX hosts")
    def test_soname_alias_does_not_duplicate_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, item = self.archive(root, {"lib/libtest.so.12.1": b"runtime",
                                               "lib/libtest.so.12": ("libtest.so.12.1",),
                                               "LICENSE": b"license"})
            cuda.install_archive(archive, root / "sdk", item, root / "extract")
            alias = root / "sdk/cuda/lib/libtest.so.12"
            self.assertTrue(alias.is_symlink())
            self.assertEqual(b"runtime", alias.read_bytes())

    def test_sdk_inventory_tampering_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            (root / "header.h").write_text("valid")
            receipt = dict(schemaVersion=1, status="PASS", system="Linux", arch="x86_64", provider="cuda",
                           lockSha256=digest(cuda.LOCK_PATH), commonLockSha256=digest(cuda.common.LOCK_PATH),
                           nvccVersion="12.1.105", configuration=cuda.engine_options(root), files=inventory(root))
            (root / "sdk-receipt.json").write_text(json.dumps(receipt))
            self.assertEqual("PASS", cuda.verify_sdk(root)["status"])
            (root / "header.h").write_text("changed")
            with self.assertRaisesRegex(ValueError, "receipt or files"):
                cuda.verify_sdk(root)

    @unittest.skipIf(os.name == "nt", "Linux nvcc layout is tested on POSIX hosts")
    def test_nvcc_lib64_layout_resolves_only_locked_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            libraries = root / "cuda/lib"
            libraries.mkdir(parents=True)
            with self.assertRaisesRegex(ValueError, "archives are missing"):
                cuda.prepare_toolkit_layout(root)
            for name in ("libcudadevrt.a", "libcudart_static.a"):
                (libraries / name).write_bytes(b"locked")
            cuda.prepare_toolkit_layout(root)
            self.assertEqual(libraries.resolve(), (root / "cuda/lib64").resolve())
            self.assertEqual(b"locked", (root / "cuda/lib64/libcudadevrt.a").read_bytes())
            with self.assertRaisesRegex(ValueError, "already exists"):
                cuda.prepare_toolkit_layout(root)

    def test_elf_external_dependencies_and_abi_are_bounded(self):
        header = "Class: ELF64\nMachine: Advanced Micro Devices X86-64\n"
        dynamic = "(NEEDED) Shared library: [libcudnn.so.9]\n(RUNPATH) Library runpath: [$ORIGIN]\n"
        versions = "GLIBC_2.34 GLIBCXX_3.4.30 CXXABI_1.3.13"
        with patch("package_katago_source_linux_cuda.subprocess.check_output", side_effect=[header, dynamic, versions]):
            self.assertEqual(["libcudnn.so.9"], audit_binary(Path("katago"))["needed"])
        for changed in ("GLIBC_2.35", "GLIBCXX_3.4.31", "CXXABI_1.3.14"):
            with self.subTest(changed=changed), patch(
                "package_katago_source_linux_cuda.subprocess.check_output", side_effect=[header, dynamic, changed]
            ):
                with self.assertRaisesRegex(ValueError, "ceiling"):
                    audit_binary(Path("katago"))
        for changed in ("libcudnn.so.8", "libmystery.so", "/sdk/libcudnn.so.9"):
            with self.subTest(changed=changed), patch(
                "package_katago_source_linux_cuda.subprocess.check_output",
                side_effect=[header, dynamic.replace("libcudnn.so.9", changed), versions]
            ):
                with self.assertRaisesRegex(ValueError, "dependency"):
                    audit_binary(Path("katago"))

    def test_external_runtime_requires_every_declared_library(self):
        with tempfile.TemporaryDirectory() as directory:
            sdk = Path(directory)
            for name in VENDOR_LIBRARIES:
                path = sdk / "cuda/lib" / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"library")
            with patch("package_katago_source_linux_cuda.audit_binary", return_value={"needed": []}):
                result = external_runtime(sdk, {"needed": ["libcudnn.so.9"]})
                self.assertEqual("external", result["mode"])
                self.assertEqual("NOT_RUN", result["gpuExecutionStatus"])
                (sdk / "cuda/lib/libcudnn.so.9").unlink()
                with self.assertRaisesRegex(ValueError, "Missing or ambiguous"):
                    external_runtime(sdk, {"needed": ["libcudnn.so.9"]})


if __name__ == "__main__":
    unittest.main()
