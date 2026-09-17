#!/usr/bin/env python3

import copy
import io
import json
from pathlib import Path, PureWindowsPath
import tempfile
import subprocess
import unittest
from unittest.mock import patch
import zipfile

import build_katago_rocm_dependencies as rocm
from build_katago_macos_dependencies import digest, inventory
from build_katago_source import build, TARGETS
from package_katago_source_windows import inspect_pe, ROCM_SYSTEM_LIBRARIES


class RocmSourceTest(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads(rocm.LOCK_PATH.read_text())
        self.target = "windows-rocm-gfx110x"

    def archive(self, root, changes=None):
        files = {name: b"existing runtime" for name in self.lock["runtimeFiles"]}
        files.update({"rocblas/library/kernel.hsaco": b"gpu kernel",
                      "hipblaslt/library/kernel.dat": b"gpu data",
                      "katago.exe": b"must never use this old engine", "README_ROCM.txt": b"runtime guidance"})
        files.update(changes or {})
        archive = root / "runtime.zip"
        with zipfile.ZipFile(archive, "w") as bundle:
            for name, data in files.items():
                if data is not None:
                    bundle.writestr(name, data)
        lock = copy.deepcopy(self.lock)
        lock["runtimes"][self.target].update(sizeBytes=archive.stat().st_size, sha256=digest(archive))
        return archive, lock

    def test_all_four_existing_families_and_versions_are_locked(self):
        self.assertEqual(set(self.lock["runtimes"]), rocm.TARGETS)
        self.assertEqual(4, len(rocm.TARGETS))
        self.assertEqual("7.13.0", self.lock["version"])
        self.assertEqual(24, len(self.lock["runtimeFiles"]))
        self.assertEqual(25, len(set(self.lock["hipArchitectures"])))
        self.assertEqual(5374290649, self.lock["sdk"]["sizeBytes"])
        for target, item in self.lock["runtimes"].items():
            self.assertIn("v1.18.1-rocm7.13-", item["url"])
            self.assertEqual(("Windows", "x86_64", "ROCM", True), TARGETS[target])
            self.assertRegex(item["sha256"], r"^[0-9a-f]{64}$")

    def test_configuration_uses_locked_compiler_and_all_architectures(self):
        options = rocm.engine_options(Path("/tmp/Chinese space/sdk"))
        self.assertTrue(any(item.endswith("rocm/lib/llvm/bin/clang++.exe") for item in options))
        self.assertIn("-DCMAKE_HIP_ARCHITECTURES=" + ";".join(self.lock["hipArchitectures"]), options)
        self.assertTrue(any(item.endswith("lib/zlibstatic.lib") for item in options))
        self.assertFalse(any(item.startswith("-DEIGEN3_INCLUDE_DIRS=") for item in options))
        self.assertFalse(any("KATAGO_WIN_MSVC_TOOLSET_CHECKED" in item for item in options))

    def test_windows_paths_are_safe_when_cmake_serializes_hip_compiler_state(self):
        prefix = PureWindowsPath(r"D:\a\_temp\SDK with spaces")
        with patch.object(Path, "resolve", return_value=prefix), \
                patch.object(rocm.common, "engine_options", return_value=[
                    r"-DZLIB_LIBRARY=D:\a\_temp\SDK with spaces\lib\zlibstatic.lib"]):
            options = rocm.engine_options(Path("ignored"))
        self.assertTrue(all("\\" not in value for value in options))
        self.assertIn("-DCMAKE_HIP_COMPILER_ROCM_ROOT=D:/a/_temp/SDK with spaces/rocm", options)
        self.assertIn("-DZLIB_LIBRARY=D:/a/_temp/SDK with spaces/lib/zlibstatic.lib", options)
        self.assertIn("-DCMAKE_HIP_COMPILER=D:/a/_temp/SDK with spaces/rocm/lib/llvm/bin/clang++.exe", options)

    def test_environment_does_not_reuse_unrelated_hip_path(self):
        prefix = Path("sdk path").resolve()
        with patch.dict(rocm.os.environ, {"HIP_PATH": "old", "ROCM_PATH": "old", "CXXFLAGS": "unsafe",
                                         "HIP_DEVICE_LIB_PATH": "old", "LLVM_PATH": "old", "HIP_PLATFORM": "nvidia"}):
            env = rocm.environment(prefix)
        self.assertEqual((prefix / "rocm").as_posix(), env["HIP_PATH"])
        self.assertEqual(env["HIP_PATH"], env["ROCM_PATH"])
        self.assertEqual("amd", env["HIP_PLATFORM"])
        self.assertEqual((prefix / "rocm/lib/llvm/amdgcn/bitcode").as_posix(), env["HIP_DEVICE_LIB_PATH"])
        self.assertEqual((prefix / "rocm/lib/llvm").as_posix(), env["LLVM_PATH"])
        self.assertNotIn("CXXFLAGS", env)
        self.assertTrue(env["PATH"].startswith(str(prefix / "runtime")))

    def test_missing_sdk_rejected_for_each_family(self):
        for target in rocm.TARGETS:
            with self.subTest(target=target), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"):
                    with self.assertRaisesRegex(ValueError, "verified pinned dependencies"):
                        build(root / "source", root / "build", target, [], 3)
                self.assertFalse((root / "build").exists())

    def test_wrong_family_sdk_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch("build_katago_source.check_host"), patch("build_katago_source.check_source"), \
                    patch.object(rocm, "verify_sdk", return_value={"target": "windows-rocm-gfx120x"}):
                with self.assertRaisesRegex(ValueError, "different GPU family"):
                    build(root / "source", root / "build", self.target, [], 3, windows_sdk=root / "sdk")
            self.assertFalse((root / "build").exists())

    def test_runtime_retains_kernel_data_but_not_the_old_engine(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, lock = self.archive(root)
            rocm.install_runtime(archive, root / "sdk", lock, self.target)
            self.assertEqual(set(self.lock["runtimeFiles"]), {path.name for path in (root / "sdk/runtime").glob("*.dll")})
            self.assertEqual(b"gpu kernel", (root / "sdk/runtime/rocblas/library/kernel.hsaco").read_bytes())
            self.assertEqual(b"gpu data", (root / "sdk/runtime/hipblaslt/library/kernel.dat").read_bytes())
            self.assertFalse(list((root / "sdk").rglob("*.exe")))

    def test_size_or_digest_failure_writes_nothing(self):
        for key, value in (("sizeBytes", 1), ("sha256", "f" * 64)):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, lock = self.archive(root)
                lock["runtimes"][self.target][key] = value
                with self.assertRaisesRegex(ValueError, "size or SHA-256"):
                    rocm.install_runtime(archive, root / "sdk", lock, self.target)
                self.assertFalse((root / "sdk").exists())

    def test_missing_dll_or_kernel_family_and_unknown_dll_rejected(self):
        for changes in ({"MIOpen.dll": None}, {"surprise.dll": b"bad"},
                        {"rocblas/library/kernel.hsaco": None}, {"hipblaslt/library/kernel.dat": None}):
            with self.subTest(changes=changes), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, lock = self.archive(root, changes)
                with self.assertRaisesRegex(ValueError, "inventory"):
                    rocm.install_runtime(archive, root / "sdk", lock, self.target)
                self.assertFalse((root / "sdk").exists())

    def test_traversal_case_collision_and_executable_in_kernel_tree_rejected(self):
        for name in ("../escape", "C:/escape", "rocblas/../escape", "MIOPEN.dll",
                     "rocblas/library/evil.exe", "rocblas\\escape", "rocblas/library/file:stream"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                archive, lock = self.archive(root, {name: b"bad"})
                with self.assertRaises(ValueError):
                    rocm.install_runtime(archive, root / "sdk", lock, self.target)
                self.assertFalse((root / "sdk").exists())

    def test_existing_runtime_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive, lock = self.archive(root)
            path = root / "sdk/runtime/MIOpen.dll"
            path.parent.mkdir(parents=True)
            path.write_bytes(b"keep")
            with self.assertRaisesRegex(ValueError, "overwrite"):
                rocm.install_runtime(archive, root / "sdk", lock, self.target)
            self.assertEqual(b"keep", path.read_bytes())

    def test_download_checks_actual_archive_before_use(self):
        with tempfile.TemporaryDirectory() as directory:
            with patch.object(rocm.subprocess, "run"), patch.object(rocm, "verify_archive", side_effect=ValueError("bad SHA")):
                with self.assertRaisesRegex(ValueError, "bad SHA"):
                    rocm.download(self.lock["sdk"], Path(directory) / "sdk.tar", io.StringIO())

    def test_unknown_family_fails_before_common_build(self):
        with patch.object(rocm.common, "build_sdk") as builder:
            with self.assertRaisesRegex(ValueError, "Unknown"):
                rocm.build_sdk(Path("output"), "windows-rocm-future", 3)
            builder.assert_not_called()

    def test_compiler_probe_keeps_diagnostics_and_never_ignores_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(rocm.subprocess, "run", side_effect=[
                    subprocess.CompletedProcess([], 0, "clang version 23.0.0\n"),
                    subprocess.CalledProcessError(1, ["clang++"])]) as run:
                log = io.StringIO()
                with self.assertRaises(subprocess.CalledProcessError):
                    rocm.probe_compiler(root, root, log)
                self.assertIn("clang version", log.getvalue())
                self.assertEqual(subprocess.STDOUT, run.call_args.kwargs["stderr"])
                self.assertTrue(run.call_args.kwargs["check"])
                self.assertIn("--offload-arch=gfx900", run.call_args.args[0])

    def test_build_rejects_changed_msvc_or_architecture_range(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / "build.log"
            cache = root / "CMakeCache.txt"
            cache.write_text("CMAKE_HIP_ARCHITECTURES:STRING=" + ";".join(self.lock["hipArchitectures"]) + "\n")
            log.write_text("MSVC toolset 14.44.35207 is compatible with the HIP compiler; using it\n")
            rocm.validate_build(root)
            for text in ("", "MSVC toolset 14.50.12345 is compatible with the HIP compiler; using it\n"):
                log.write_text(text)
                with self.assertRaisesRegex(ValueError, "toolset"):
                    rocm.validate_build(root)
            log.write_text("MSVC toolset 14.44.35207 is compatible with the HIP compiler; using it\n")
            cache.write_text("CMAKE_HIP_ARCHITECTURES:STRING=gfx1100\n")
            with self.assertRaisesRegex(ValueError, "architecture range"):
                rocm.validate_build(root)

    def test_sealed_sdk_rejects_tampering_or_wrong_family(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            header = root / "header.h"
            header.write_text("original")
            receipt = dict(schemaVersion=1, status="PASS", provider="rocm", system="Windows", arch="x86_64",
                           target=self.target, lockSha256=digest(rocm.LOCK_PATH),
                           commonLockSha256=digest(rocm.common.LOCK_PATH),
                           configuration=rocm.engine_options(root), files=inventory(root))
            (root / "sdk-receipt.json").write_text(json.dumps(receipt))
            self.assertEqual(self.target, rocm.verify_sdk(root)["target"])
            header.write_text("modified")
            with self.assertRaisesRegex(ValueError, "locked build"):
                rocm.verify_sdk(root)
            header.write_text("original")
            receipt["target"] = "windows-cpu"
            (root / "sdk-receipt.json").write_text(json.dumps(receipt))
            with self.assertRaisesRegex(ValueError, "locked build"):
                rocm.verify_sdk(root)

    def test_pe_audit_still_rejects_missing_or_foreign_runtime(self):
        inspect_pe("8664 machine (x64)", "MIOpen.dll\nKERNEL32.dll\n", set(rocm.runtime_files()), ROCM_SYSTEM_LIBRARIES)
        for name in ("cudart64_12.dll", "unknown.dll"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Unapproved"):
                inspect_pe("8664 machine (x64)", name + "\n", set(rocm.runtime_files()), ROCM_SYSTEM_LIBRARIES)


if __name__ == "__main__":
    unittest.main()
