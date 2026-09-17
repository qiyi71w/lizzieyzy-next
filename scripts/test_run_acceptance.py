#!/usr/bin/env python3
"""Focused tests for the local desktop acceptance runner."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


SCRIPT_DIR = Path(__file__).resolve().parent
MODULE_PATH = SCRIPT_DIR / "run_acceptance.py"
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))
SPEC = importlib.util.spec_from_file_location("run_acceptance", MODULE_PATH)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load {MODULE_PATH}")
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class FakeChild:
    def __init__(self, *, timeout: bool = False, return_code: int = 0) -> None:
        self.pid = 4242
        self.timeout = timeout
        self.return_code = return_code
        self.wait_calls = 0

    def wait(self, timeout: int | None = None) -> int:
        self.wait_calls += 1
        if self.timeout and timeout is not None and self.wait_calls == 1:
            raise MODULE.subprocess.TimeoutExpired(["mvn"], timeout)
        return self.return_code


class AcceptanceRunnerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="run-acceptance-test.")
        self.root = Path(self.temp.name).resolve()
        # The runner is Linux-only; its unit tests use a fake child on every host.
        self.killpg_patch = mock.patch.object(MODULE.os, "killpg", create=True)
        self.killpg_patch.start()
        self.addCleanup(self.killpg_patch.stop)
        self.sigkill_patch = mock.patch.object(MODULE.signal, "SIGKILL", 9, create=True)
        self.sigkill_patch.start()
        self.addCleanup(self.sigkill_patch.stop)
        self.engine = self.root / "katago"
        self.engine.write_bytes(b"engine")
        self.engine.chmod(self.engine.stat().st_mode | stat.S_IXUSR)
        self.model = self.root / "model.bin.gz"
        self.model.write_bytes(b"model")

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def sha256(path: Path) -> str:
        return hashlib.sha256(path.read_bytes()).hexdigest()

    def d4_closure(self) -> dict[str, Path]:
        archive = self.root / "katago.zip"
        archive.write_bytes(b"archive")
        config = self.root / "default_gtp.cfg"
        config.write_bytes(b"rules = chinese\n")
        catalog = self.root / "katago-assets.json"
        catalog_record = {
            "schemaVersion": 1,
            "katagoVersion": "1.18.1",
            "katagoReleaseTag": "v1.18.1",
            "katagoSourceCommit": "source-commit",
            "defaultModelId": "fixture-model",
            "models": {
                "fixture-model": {
                    "fileName": self.model.name,
                    "minimumKataGoVersion": "1.17.0",
                    "sizeBytes": self.model.stat().st_size,
                    "sha256": self.sha256(self.model),
                }
            },
            "assets": {
                "linux-cpu": {
                    "backend": "eigen",
                    "assetName": archive.name,
                    "sizeBytes": archive.stat().st_size,
                    "sha256": self.sha256(archive),
                }
            },
        }
        catalog.write_text(json.dumps(catalog_record), encoding="utf-8")
        manifest = self.root / "manifest.json"
        manifest_record = {
            "schemaVersion": 1,
            "catalog": {"path": str(catalog), "sha256": self.sha256(catalog)},
            "katago": {
                "version": "1.18.1",
                "releaseTag": "v1.18.1",
                "sourceCommit": "source-commit",
                "backend": "eigen",
                "archive": {
                    "fileName": archive.name,
                    "path": str(archive),
                    "sizeBytes": archive.stat().st_size,
                    "sha256": self.sha256(archive),
                },
                "executable": {
                    "path": str(self.engine),
                    "sizeBytes": self.engine.stat().st_size,
                    "sha256": self.sha256(self.engine),
                    "versionOutput": "KataGo v1.18.1\nUsing Eigen(CPU) backend",
                },
            },
            "model": {
                "id": "fixture-model",
                "fileName": self.model.name,
                "minimumKataGoVersion": "1.17.0",
                "path": str(self.model),
                "sizeBytes": self.model.stat().st_size,
                "sha256": self.sha256(self.model),
            },
            "config": {
                "sourceArchiveEntry": "default_gtp.cfg",
                "path": str(config),
                "sizeBytes": config.stat().st_size,
                "sha256": self.sha256(config),
            },
            "preparedAt": "2026-09-16T00:00:00+00:00",
            "networkUsed": False,
        }
        manifest.write_text(json.dumps(manifest_record), encoding="utf-8")
        return {"catalog": catalog, "config": config, "manifest": manifest}


    def args(self, **overrides: object) -> argparse.Namespace:
        values = {
            "scenario": None,
            "engine": self.engine,
            "model": self.model,
            "engine_config": None,
            "engine_manifest": None,
            "output": None,
            "timeout": 600,
        }
        values.update(overrides)
        return argparse.Namespace(**values)

    def test_implicit_plan_keeps_only_the_original_three_scenarios(self) -> None:
        plan = MODULE.build_plan(
            self.args(), self.root / "evidence", "/usr/bin/xvfb-run", "/usr/bin/mvn"
        )

        self.assertEqual(
            ("search", "settings", "quick-analysis"), plan.selected
        )
        selector = next(argument for argument in plan.command if argument.startswith("-Dtest="))
        self.assertEqual(
            "-Dtest="
            "FunctionSearchInputTest#chineseInputChain+englishInputChain,"
            "ConfigPersistenceAcceptanceTest#savesSettingAcrossRestart,"
            "QuickAnalysisAcceptanceIT#resumesForegroundAfterImportedGame+preservesExplicitPauseDuringQuickAnalysis",
            selector,
        )
        self.assertNotIn("SgfUiAcceptanceIT", selector)
        self.assertNotIn("RealCpuEngineAcceptanceIT", selector)


    def test_opt_in_scenarios_use_exact_methods_and_d3_needs_no_assets(self) -> None:
        d3 = MODULE.build_plan(
            self.args(scenario=["sgf-ui"], engine=None, model=None),
            self.root / "d3-evidence",
            "/usr/bin/xvfb-run",
            "/usr/bin/mvn",
        )

        self.assertEqual(("sgf-ui",), d3.selected)
        self.assertIn(
            "-Dtest=SgfUiAcceptanceIT#opensBranchesAnalyzesSavesCancelsOverwriteAndReopens",
            d3.command,
        )
        self.assertFalse(
            any(argument.startswith("-Dlizzie.acceptance.engine=") for argument in d3.command)
        )

        d4_cases = MODULE.SCENARIOS["real-cpu-engine"]
        self.assertEqual(
            (("RealCpuEngineAcceptanceIT", "analyzesPinnedCpuEngineThroughProductionOwnership"),),
            d4_cases,
        )
    def test_real_cpu_engine_preflight_requires_and_verifies_all_four_inputs(self) -> None:
        closure = self.d4_closure()
        complete = {
            "scenario": ["real-cpu-engine"],
            "engine": self.engine,
            "model": self.model,
            "engine_config": closure["config"],
            "engine_manifest": closure["manifest"],
        }
        with mock.patch.object(MODULE, "ASSET_CATALOG", closure["catalog"]):
            for field, option in (
                ("engine", "--engine"),
                ("model", "--model"),
                ("engine_config", "--engine-config"),
                ("engine_manifest", "--engine-manifest"),
            ):
                missing = dict(complete)
                missing[field] = None
                with self.subTest(missing=option), self.assertRaisesRegex(RuntimeError, option):
                    MODULE.build_plan(
                        self.args(**missing),
                        self.root / f"missing-{field}",
                        "/usr/bin/xvfb-run",
                        "/usr/bin/mvn",
                    )

            manifest_record = json.loads(closure["manifest"].read_text(encoding="utf-8"))
            manifest_record["model"]["sha256"] = "0" * 64
            closure["manifest"].write_text(json.dumps(manifest_record), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "model"):
                MODULE.build_plan(
                    self.args(**complete),
                    self.root / "mismatch",
                    "/usr/bin/xvfb-run",
                    "/usr/bin/mvn",
                )

    def test_mixed_plan_preserves_properties_and_controlled_real_identity(self) -> None:
        closure = self.d4_closure()
        with mock.patch.object(MODULE, "ASSET_CATALOG", closure["catalog"]):
            plan = MODULE.build_plan(
                self.args(
                    scenario=["sgf-ui", "real-cpu-engine"],
                    engine_config=closure["config"],
                    engine_manifest=closure["manifest"],
                ),
                self.root / "mixed",
                "/usr/bin/xvfb-run",
                "/usr/bin/mvn",
            )

        self.assertEqual(
            {
                "sgf-ui": ("controlled", "controlled-java"),
                "real-cpu-engine": ("real", "real-katago-cpu"),
            },
            {
                name: (details["execution"], details["peer_kind"])
                for name, details in plan.scenario_results.items()
            },
        )
        self.assertIn(
            "-Dtest="
            "SgfUiAcceptanceIT#opensBranchesAnalyzesSavesCancelsOverwriteAndReopens,"
            "RealCpuEngineAcceptanceIT#analyzesPinnedCpuEngineThroughProductionOwnership",
            plan.command,
        )
        self.assertIn(f"-Dlizzie.acceptance.engine={self.engine}", plan.command)
        self.assertIn(f"-Dlizzie.acceptance.model={self.model}", plan.command)
        self.assertIn(
            f"-Dlizzie.acceptance.engine.config={closure['config']}", plan.command
        )
        self.assertIn(
            f"-Dlizzie.acceptance.engine.manifest={closure['manifest']}", plan.command
        )

        quick = MODULE.build_plan(
            self.args(scenario=["quick-analysis"]),
            self.root / "quick",
            "/usr/bin/xvfb-run",
            "/usr/bin/mvn",
        )
        self.assertFalse(
            any("engine.config" in argument or "engine.manifest" in argument for argument in quick.command)
        )

    def test_missing_d4_input_is_blocked_before_maven_spawn(self) -> None:
        closure = self.d4_closure()
        output = self.root / "blocked"
        args = self.args(
            scenario=["real-cpu-engine"],
            output=output,
            engine_config=closure["config"],
            engine_manifest=None,
        )
        with (
            mock.patch.object(MODULE.platform, "platform", return_value="Linux-test"),
            mock.patch.object(MODULE.platform, "system", return_value="Linux"),
            mock.patch.object(MODULE.shutil, "which", side_effect=lambda name: f"/usr/bin/{name}"),
            mock.patch.object(MODULE, "resolve_maven", return_value="/usr/bin/mvn"),
            mock.patch.object(MODULE, "java_major_version", return_value=(21, "OpenJDK 21")),
            mock.patch.object(MODULE, "git_output", return_value="clean"),
            mock.patch.object(MODULE.subprocess, "Popen") as popen,
        ):
            self.assertEqual(1, MODULE.run(args))

        popen.assert_not_called()
        record = json.loads((output / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("BLOCKED", record["result"])
        self.assertEqual("BLOCKED", record["scenario_results"]["real-cpu-engine"]["status"])
        self.assertIn("--engine-manifest", record["error"])

    def test_required_junit_failures_and_timeout_remain_fail_with_evidence(self) -> None:
        classname = "featurecat.lizzie.gui.FunctionSearchInputTest"
        reports = {
            "missing": (
                f'<testsuite tests="1" failures="0" errors="0" skipped="0">'
                f'<testcase classname="{classname}" name="chineseInputChain"/>'
                "</testsuite>"
            ),
            "skipped": (
                f'<testsuite tests="2" failures="0" errors="0" skipped="1">'
                f'<testcase classname="{classname}" name="chineseInputChain"><skipped/></testcase>'
                f'<testcase classname="{classname}" name="englishInputChain"/>'
                "</testsuite>"
            ),
            "failing": (
                f'<testsuite tests="2" failures="1" errors="0" skipped="0">'
                f'<testcase classname="{classname}" name="chineseInputChain"><failure/></testcase>'
                f'<testcase classname="{classname}" name="englishInputChain"/>'
                "</testsuite>"
            ),
            "malformed": "<testsuite",
        }
        for name, xml in reports.items():
            with self.subTest(result=name):
                output = self.root / name

                def popen(*_args: object, **_kwargs: object) -> FakeChild:
                    reports_dir = output / "surefire-reports"
                    reports_dir.mkdir(parents=True)
                    (reports_dir / "TEST-search.xml").write_text(xml, encoding="utf-8")
                    return FakeChild()

                with (
                    mock.patch.object(MODULE.platform, "platform", return_value="Linux-test"),
                    mock.patch.object(MODULE.platform, "system", return_value="Linux"),
                    mock.patch.object(
                        MODULE.shutil, "which", side_effect=lambda tool: f"/usr/bin/{tool}"
                    ),
                    mock.patch.object(MODULE, "resolve_maven", return_value="/usr/bin/mvn"),
                    mock.patch.object(
                        MODULE, "java_major_version", return_value=(21, "OpenJDK 21")
                    ),
                    mock.patch.object(MODULE, "git_output", return_value="clean"),
                    mock.patch.object(MODULE.subprocess, "Popen", side_effect=popen),
                    mock.patch.object(MODULE.os, "killpg"),
                    mock.patch.object(MODULE.time, "sleep"),
                ):
                    self.assertEqual(1, MODULE.run(self.args(scenario=["search"], output=output)))

                record = json.loads((output / "acceptance.json").read_text(encoding="utf-8"))
                self.assertEqual("FAIL", record["result"])
                self.assertEqual("FAIL", record["scenario_results"]["search"]["status"])
                self.assertTrue((output / "maven.log").is_file())
                self.assertTrue((output / "surefire-reports" / "TEST-search.xml").is_file())

        output = self.root / "timeout"
        child = FakeChild(timeout=True)
        with (
            mock.patch.object(MODULE.platform, "platform", return_value="Linux-test"),
            mock.patch.object(MODULE.platform, "system", return_value="Linux"),
            mock.patch.object(MODULE.shutil, "which", side_effect=lambda tool: f"/usr/bin/{tool}"),
            mock.patch.object(MODULE, "resolve_maven", return_value="/usr/bin/mvn"),
            mock.patch.object(MODULE, "java_major_version", return_value=(21, "OpenJDK 21")),
            mock.patch.object(MODULE, "git_output", return_value="clean"),
            mock.patch.object(MODULE.subprocess, "Popen", return_value=child),
            mock.patch.object(MODULE.os, "killpg") as killpg,
            mock.patch.object(MODULE.time, "sleep"),
        ):
            self.assertEqual(
                1, MODULE.run(self.args(scenario=["search"], output=output, timeout=1))
            )

        record = json.loads((output / "acceptance.json").read_text(encoding="utf-8"))
        self.assertEqual("FAIL", record["result"])
        self.assertIn("Acceptance exceeded 1s", record["error"])
        self.assertEqual(
            [mock.call(child.pid, MODULE.signal.SIGTERM), mock.call(child.pid, MODULE.signal.SIGKILL)],
            killpg.call_args_list,
        )

    def test_opt_in_result_collection_rejects_missing_probe_records(self) -> None:
        output = self.root / "missing-probes"
        (output / "probes").mkdir(parents=True)
        scenario_results = MODULE._initial_scenario_results(("sgf-ui", "real-cpu-engine"))

        with self.assertRaisesRegex(RuntimeError, "sgf-ui.*result"):
            MODULE.collect_scenario_evidence(
                output, ("sgf-ui",), scenario_results, inputs={}
            )
        with self.assertRaisesRegex(RuntimeError, "real-cpu-engine.*result"):
            MODULE.collect_scenario_evidence(
                output, ("real-cpu-engine",), scenario_results, inputs={}
            )

if __name__ == "__main__":
    unittest.main()
