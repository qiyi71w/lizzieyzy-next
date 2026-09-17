import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import zipfile

from build_katago_source import SOURCE_COMMIT, TARGETS
from katago_asset_catalog import DEFAULT_CATALOG
from stage_katago_source_release import archive_files, normalized_name, record, stage, verify_archive


class SourceReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.packages = self.root / "packages"
        self.acceptance = self.root / "acceptance"
        self.source = self.root / "source"
        (self.source / "cpp/configs").mkdir(parents=True)
        (self.source / "cpp/configs/gtp_example.cfg").write_text("gtp test config", encoding="utf-8")
        (self.source / "cpp/configs/analysis_example.cfg").write_text("analysis test config", encoding="utf-8")
        source_check = mock.patch("stage_katago_source_release.check_source")
        self.source_check = source_check.start()
        self.addCleanup(source_check.stop)
        for target, (_, _, backend, experimental) in TARGETS.items():
            directory = self.packages / target
            directory.mkdir(parents=True)
            name = "katago.exe" if target.startswith("windows-") else "katago"
            (directory / name).write_bytes(b"synthetic test binary, not real acceptance " + target.encode())
            (directory / "licenses").mkdir()
            (directory / "licenses/LICENSE").write_text("test license", encoding="utf-8")
            receipt = dict(
                schemaVersion=1, target=target, backend=backend, experimental=experimental,
                sourceRepository="https://github.com/lightvector/KataGo", sourceCommit=SOURCE_COMMIT,
                origin="project-source-build", buildStatus="PASS", packagingStatus="PASS",
                dependencyAuditStatus="PASS", hardwareAcceptanceStatus="NOT_RUN",
                dependencyLockSha256="a" * 64, compiler={"CMAKE_CXX_COMPILER_ID": "test"},
                versionOutput=f"KataGo v1.18.2\nGit revision: {SOURCE_COMMIT}\n",
                executable=record(directory / name, name),
                files=[record(path, path.relative_to(directory).as_posix())
                       for path in sorted(directory.rglob("*")) if path.is_file()])
            self.receipt_path(target).write_text(json.dumps(receipt), encoding="utf-8")
            evidence = self.acceptance / target
            evidence.mkdir(parents=True)
            identity = dict(sourceCommit=SOURCE_COMMIT, executableSha256=receipt["executable"]["sha256"])
            hardware = dict(identity, status="PENDING_HARDWARE", reason="synthetic test host has no GPU")
            if backend == "EIGEN":
                hardware = dict(identity, status="PASS", steps=[
                    {"command": "kata-analyze rootInfo true " + focus, "rootVisits": (i + 1) * 10}
                    for i, focus in enumerate(("", "focus Q4 0.5", "focus Q4,D16 0.5", "focus D16 0.5", ""))])
            (evidence / "hardware.json").write_text(json.dumps(hardware), encoding="utf-8")
            if target.startswith("linux-"):
                (evidence / "linux-compatibility.json").write_text(json.dumps(dict(
                    identity, status="PASS", baselineExecutableSha256="b" * 64,
                    symbolCeilings={"GLIBC": "2.34"}, distributionChecks=[{"status": "PASS", "name": "test"}])),
                    encoding="utf-8")

    def receipt_path(self, target):
        return self.packages / target / ("source-build.json" if target.startswith("macos-") else "source-package.json")

    def modify(self, path, **values):
        data = json.loads(path.read_text(encoding="utf-8"))
        data.update(values)
        path.write_text(json.dumps(data), encoding="utf-8")

    def run_stage(self, output="release"):
        return stage(self.packages, self.acceptance, DEFAULT_CATALOG, self.root / output,
                     "next-2026-09-17.1", self.source)

    def test_all_fifteen_targets_are_sealed_and_model_is_unchanged(self):
        catalog = self.run_stage()
        self.assertEqual(set(TARGETS), set(catalog["assets"]))
        baseline = json.loads(DEFAULT_CATALOG.read_text(encoding="utf-8"))
        self.assertEqual(baseline["models"], catalog["models"])
        for target, asset in catalog["assets"].items():
            archive = self.root / "release" / asset["assetName"]
            self.assertEqual(asset["sha256"], record(archive, archive.name)["sha256"])
            with zipfile.ZipFile(archive) as opened:
                metadata = json.loads(opened.read("source-release.json"))
                self.assertEqual(target, metadata["target"])
                self.assertIn("licenses/LICENSE", opened.namelist())
                self.assertEqual(b"gtp test config", opened.read("default_gtp.cfg"))
                self.assertEqual(b"analysis test config", opened.read("analysis_example.cfg"))
                if target.startswith("macos-") or target.startswith("linux-"):
                    self.assertEqual(0o755, (opened.getinfo("katago").external_attr >> 16) & 0o777)

    def test_archives_are_deterministic(self):
        first, second = self.run_stage("first"), self.run_stage("second")
        self.assertEqual(first, second)

    def test_configs_must_come_from_pinned_clean_source(self):
        self.source_check.side_effect = ValueError("incorrect source HEAD")
        with self.assertRaisesRegex(ValueError, "source HEAD"):
            self.run_stage()
        self.assertFalse((self.root / "release").exists())

    def test_missing_config_prevents_incomplete_repair_archive(self):
        (self.source / "cpp/configs/gtp_example.cfg").unlink()
        with self.assertRaisesRegex(ValueError, "templates missing"):
            self.run_stage()

    def test_archive_is_reopened_and_checksum_checked(self):
        catalog = self.run_stage()
        target = "windows-cpu"
        original = self.root / "release" / catalog["assets"][target]["assetName"]
        corrupted = self.root / "corrupted.zip"
        with zipfile.ZipFile(original) as source, zipfile.ZipFile(corrupted, "w") as output:
            for item in source.infolist():
                data = source.read(item)
                output.writestr(item, b"x" * len(data) if item.filename == "katago.exe" else data)
        with self.assertRaisesRegex(ValueError, "checksum mismatch"):
            verify_archive(corrupted, target)

    def test_existing_output_is_never_overwritten(self):
        self.run_stage()
        with self.assertRaisesRegex(ValueError, "must be new"):
            self.run_stage()

    def test_modified_binary_fails_before_output_creation(self):
        (self.packages / "windows-cpu/katago.exe").write_bytes(b"modified")
        with self.assertRaisesRegex(ValueError, "modified package"):
            self.run_stage()
        self.assertFalse((self.root / "release").exists())

    def test_missing_target_fails_closed(self):
        self.receipt_path("windows-rocm-gfx120x").unlink()
        with self.assertRaises(FileNotFoundError):
            self.run_stage()
        self.assertFalse((self.root / "release").exists())

    def test_extra_file_and_symlink_are_rejected(self):
        extra = self.packages / "windows-cpu/old.dll"
        extra.write_bytes(b"old")
        with self.assertRaisesRegex(ValueError, "exact package"):
            self.run_stage()
        extra.unlink()
        extra.symlink_to(self.packages / "windows-cpu/katago.exe")
        with self.assertRaisesRegex(ValueError, "symlink"):
            self.run_stage()

    def test_inventory_case_collision_is_rejected(self):
        path = self.receipt_path("windows-cpu")
        data = json.loads(path.read_text(encoding="utf-8"))
        data["files"].append(dict(data["files"][0], file="KATAGO.EXE"))
        self.modify(path, files=data["files"])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.run_stage()

    def test_windows_inventory_separator_is_supported(self):
        path = self.receipt_path("windows-cpu")
        data = json.loads(path.read_text(encoding="utf-8"))
        for item in data["files"]:
            item["file"] = item["file"].replace("/", "\\")
        self.modify(path, files=data["files"])
        self.run_stage()

    def test_unsafe_paths_rejected(self):
        for name in ("../a", "/a", "a//b", "a/./b", "C:\\foo", "a:stream", "a. ", ""):
            with self.subTest(name=name), self.assertRaises(ValueError):
                normalized_name(name)

    def test_old_engine_or_failed_audit_cannot_be_promoted(self):
        path = self.receipt_path("windows-cpu")
        original = path.read_text(encoding="utf-8")
        for key, value in (("sourceCommit", "0" * 40), ("dependencyAuditStatus", "FAIL"),
                           ("compiler", {}), ("hardwareAcceptanceStatus", "FAIL")):
            path.write_text(original, encoding="utf-8")
            self.modify(path, **{key: value})
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.run_stage()

    def test_cpu_cannot_use_missing_gpu_waiver(self):
        self.modify(self.acceptance / "windows-cpu/hardware.json", status="PENDING_HARDWARE", reason="no GPU")
        with self.assertRaisesRegex(ValueError, "CPU execution"):
            self.run_stage()

    def test_pending_gpu_requires_reason(self):
        self.modify(self.acceptance / "windows-opencl/hardware.json", reason=" ")
        with self.assertRaisesRegex(ValueError, "require a reason"):
            self.run_stage()

    def test_acceptance_must_match_actual_executable(self):
        self.modify(self.acceptance / "windows-cpu/hardware.json", executableSha256="0" * 64)
        with self.assertRaisesRegex(ValueError, "different executable"):
            self.run_stage()

    def test_missing_inference_evidence_cannot_be_handwritten_pass(self):
        self.modify(self.acceptance / "windows-cpu/hardware.json", steps=[])
        with self.assertRaisesRegex(ValueError, "incomplete real inference"):
            self.run_stage()

    def test_onnx_cpu_does_not_claim_gpu_acceptance(self):
        target = "windows-directml"
        self.modify(self.acceptance / target / "hardware.json", status="PASS", onnxProvider="cpu")
        with self.assertRaisesRegex(ValueError, "cannot certify"):
            self.run_stage()

    def test_linux_requires_real_compatibility_record(self):
        self.modify(self.acceptance / "linux-cpu/linux-compatibility.json", distributionChecks=[])
        with self.assertRaisesRegex(ValueError, "distribution verification"):
            self.run_stage()

    def test_runtime_is_not_duplicated_in_cuda_and_trt_repair_archives(self):
        files = {"katago.exe": Path("a"), "vendor.dll": Path("b"), "licenses/vendor": Path("c")}
        for target in ("windows-nvidia", "windows-tensorrt"):
            self.assertEqual({"katago.exe", "licenses/vendor"}, set(archive_files(target, files)))
        self.assertEqual(files, archive_files("windows-rocm-gfx120x", files))


if __name__ == "__main__":
    unittest.main()
