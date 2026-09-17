#!/usr/bin/env python3
"""Run bounded local desktop acceptance on a private Linux Xvfb display."""
from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import tempfile
import time

from run_local_ci import (
    REPO_ROOT,
    RequiredJunitExecutionError,
    collect_junit_summary,
    git_output,
    java_major_version,
    resolve_maven,
)

DEFAULT_SCENARIOS = ("search", "settings", "quick-analysis")
ASSET_CATALOG = REPO_ROOT / "src" / "main" / "resources" / "katago-assets.json"


SCENARIOS = {
    "search": (
        ("FunctionSearchInputTest", "chineseInputChain"),
        ("FunctionSearchInputTest", "englishInputChain"),
    ),
    "settings": (("ConfigPersistenceAcceptanceTest", "savesSettingAcrossRestart"),),
    "quick-analysis": (
        ("QuickAnalysisAcceptanceIT", "resumesForegroundAfterImportedGame"),
        ("QuickAnalysisAcceptanceIT", "preservesExplicitPauseDuringQuickAnalysis"),
    ),
    "sgf-ui": (
        ("SgfUiAcceptanceIT", "opensBranchesAnalyzesSavesCancelsOverwriteAndReopens"),
    ),
    "real-cpu-engine": (
        ("RealCpuEngineAcceptanceIT", "analyzesPinnedCpuEngineThroughProductionOwnership"),
    ),
}

@dataclass(frozen=True)
class RunPlan:
    selected: tuple[str, ...]
    command: tuple[str, ...]
    inputs: dict[str, object]
    scenario_results: dict[str, dict[str, object]]


def _test_selector(cases: list[tuple[str, str]]) -> str:
    methods_by_class: dict[str, list[str]] = {}
    for class_name, method_name in cases:
        methods_by_class.setdefault(class_name, []).append(method_name)
    return ",".join(
        f"{class_name}#{'+'.join(methods)}"
        for class_name, methods in methods_by_class.items()
    )

def _initial_scenario_results(selected: tuple[str, ...]) -> dict[str, dict[str, object]]:
    results: dict[str, dict[str, object]] = {}
    for name in selected:
        details: dict[str, object] = {
            "status": "BLOCKED",
            "required_cases": [
                f"featurecat.lizzie.gui.{class_name}#{method_name}"
                for class_name, method_name in SCENARIOS[name]
            ],
        }
        if name == "sgf-ui":
            details.update(execution="controlled", peer_kind="controlled-java")
        elif name == "real-cpu-engine":
            details.update(execution="real", peer_kind="real-katago-cpu")
        results[name] = details
    return results

def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_object(value: object, label: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise RuntimeError(f"{label} must be a JSON object")
    return value


def _require_exact_keys(value: dict[str, object], label: str, expected: set[str]) -> None:
    actual = set(value)
    if actual != expected:
        raise RuntimeError(
            f"{label} keys differ: missing {sorted(expected - actual)}, unknown {sorted(actual - expected)}"
        )


def _require_text(value: dict[str, object], key: str, label: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result.strip():
        raise RuntimeError(f"{label} is missing non-empty {key}")
    return result.strip()


def _require_size(value: dict[str, object], label: str) -> int:
    result = value.get("sizeBytes")
    if not isinstance(result, int) or isinstance(result, bool) or result <= 0:
        raise RuntimeError(f"{label} has invalid sizeBytes")
    return result


def _require_sha256(value: dict[str, object], label: str) -> str:
    result = _require_text(value, "sha256", label)
    if len(result) != 64 or any(character not in "0123456789abcdef" for character in result):
        raise RuntimeError(f"{label} has invalid sha256")
    return result


def _require_file(path: Path, label: str, *, executable: bool = False) -> Path:
    path = path.resolve()
    if not path.is_file() or not os.access(path, os.R_OK):
        raise RuntimeError(f"{label} is not a readable regular file: {path}")
    if executable and not os.access(path, os.X_OK):
        raise RuntimeError(f"{label} is not executable: {path}")
    return path


def _load_json(path: Path, label: str) -> dict[str, object]:
    try:
        return _require_object(json.loads(path.read_text(encoding="utf-8")), label)
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"cannot read {label} {path}: {error}") from error


def _verify_identity(
    path: Path, identity: dict[str, object], label: str, *, executable: bool = False
) -> Path:
    path = _require_file(path, label, executable=executable)
    expected_path = Path(_require_text(identity, "path", label)).resolve()
    if path != expected_path:
        raise RuntimeError(f"{label} path does not match manifest: expected {expected_path}, got {path}")
    if path.stat().st_size != _require_size(identity, label):
        raise RuntimeError(f"{label} size does not match manifest")
    if _sha256_file(path) != _require_sha256(identity, label):
        raise RuntimeError(f"{label} SHA-256 does not match manifest")
    return path


def _validated_d4_inputs(args: argparse.Namespace) -> tuple[dict[str, object], list[str]]:
    options = (
        ("engine", "--engine"),
        ("model", "--model"),
        ("engine_config", "--engine-config"),
        ("engine_manifest", "--engine-manifest"),
    )
    for attribute, option in options:
        if not getattr(args, attribute, None):
            raise RuntimeError(f"real-cpu-engine requires {option}")

    engine = _require_file(args.engine, "engine", executable=True)
    model = _require_file(args.model, "model")
    config = _require_file(args.engine_config, "engine config")
    manifest_path = _require_file(args.engine_manifest, "engine manifest")
    manifest = _load_json(manifest_path, "engine manifest")
    _require_exact_keys(
        manifest,
        "engine manifest",
        {"schemaVersion", "catalog", "katago", "model", "config", "preparedAt", "networkUsed"},
    )
    if manifest.get("schemaVersion") != 1:
        raise RuntimeError("engine manifest schemaVersion must be 1")
    _require_text(manifest, "preparedAt", "engine manifest")
    if not isinstance(manifest.get("networkUsed"), bool):
        raise RuntimeError("engine manifest networkUsed must be boolean")

    manifest_catalog = _require_object(manifest["catalog"], "manifest catalog")
    _require_exact_keys(manifest_catalog, "manifest catalog", {"path", "sha256"})
    catalog_path = _require_file(
        Path(_require_text(manifest_catalog, "path", "manifest catalog")), "asset catalog"
    )
    if catalog_path != ASSET_CATALOG.resolve():
        raise RuntimeError("manifest catalog path does not identify the checked-in catalog")
    if _sha256_file(catalog_path) != _require_sha256(manifest_catalog, "manifest catalog"):
        raise RuntimeError("catalog SHA-256 does not match manifest")
    catalog = _load_json(catalog_path, "asset catalog")
    if catalog.get("schemaVersion") != 1:
        raise RuntimeError("asset catalog schemaVersion must be 1")
    catalog_assets = _require_object(catalog.get("assets"), "asset catalog assets")
    catalog_asset = _require_object(catalog_assets.get("linux-cpu"), "asset catalog linux-cpu")
    catalog_models = _require_object(catalog.get("models"), "asset catalog models")
    model_id = _require_text(catalog, "defaultModelId", "asset catalog")
    catalog_model = _require_object(catalog_models.get(model_id), f"asset catalog model {model_id}")

    katago = _require_object(manifest["katago"], "manifest katago")
    _require_exact_keys(
        katago,
        "manifest katago",
        {"version", "releaseTag", "sourceCommit", "backend", "archive", "executable"},
    )
    version = _require_text(catalog, "katagoVersion", "asset catalog")
    if (
        _require_text(katago, "version", "manifest katago") != version
        or _require_text(katago, "releaseTag", "manifest katago")
        != _require_text(catalog, "katagoReleaseTag", "asset catalog")
        or _require_text(katago, "sourceCommit", "manifest katago")
        != _require_text(catalog, "katagoSourceCommit", "asset catalog")
        or _require_text(katago, "backend", "manifest katago") != "eigen"
        or _require_text(catalog_asset, "backend", "asset catalog linux-cpu") != "eigen"
    ):
        raise RuntimeError("manifest KataGo identity does not match catalog pins")

    archive = _require_object(katago["archive"], "manifest archive")
    _require_exact_keys(archive, "manifest archive", {"fileName", "path", "sizeBytes", "sha256"})
    if (
        _require_text(archive, "fileName", "manifest archive")
        != _require_text(catalog_asset, "assetName", "asset catalog linux-cpu")
        or _require_size(archive, "manifest archive")
        != _require_size(catalog_asset, "asset catalog linux-cpu")
        or _require_sha256(archive, "manifest archive")
        != _require_sha256(catalog_asset, "asset catalog linux-cpu")
    ):
        raise RuntimeError("manifest archive identity does not match catalog pins")
    _verify_identity(Path(_require_text(archive, "path", "manifest archive")), archive, "archive")

    executable_identity = _require_object(katago["executable"], "manifest engine")
    _require_exact_keys(
        executable_identity, "manifest engine", {"path", "sizeBytes", "sha256", "versionOutput"}
    )
    engine = _verify_identity(engine, executable_identity, "engine", executable=True)
    version_output = _require_text(executable_identity, "versionOutput", "manifest engine")
    if f"KataGo v{version}" not in version_output or "Using Eigen(CPU) backend" not in version_output:
        raise RuntimeError("engine version/backend does not match manifest")

    model_identity = _require_object(manifest["model"], "manifest model")
    _require_exact_keys(
        model_identity,
        "manifest model",
        {"id", "fileName", "minimumKataGoVersion", "path", "sizeBytes", "sha256"},
    )
    if (
        _require_text(model_identity, "id", "manifest model") != model_id
        or _require_text(model_identity, "fileName", "manifest model")
        != _require_text(catalog_model, "fileName", "asset catalog model")
        or _require_text(model_identity, "minimumKataGoVersion", "manifest model")
        != _require_text(catalog_model, "minimumKataGoVersion", "asset catalog model")
        or _require_size(model_identity, "manifest model")
        != _require_size(catalog_model, "asset catalog model")
        or _require_sha256(model_identity, "manifest model")
        != _require_sha256(catalog_model, "asset catalog model")
    ):
        raise RuntimeError("manifest model identity does not match catalog pins")
    model = _verify_identity(model, model_identity, "model")

    config_identity = _require_object(manifest["config"], "manifest config")
    _require_exact_keys(
        config_identity,
        "manifest config",
        {"sourceArchiveEntry", "path", "sizeBytes", "sha256"},
    )
    if _require_text(config_identity, "sourceArchiveEntry", "manifest config") != "default_gtp.cfg":
        raise RuntimeError("manifest config is not archive default_gtp.cfg")
    config = _verify_identity(config, config_identity, "config")

    identity = {
        "engine": str(engine),
        "model": str(model),
        "engine_config": str(config),
        "engine_manifest": str(manifest_path),
        "d4_manifest": {
            "path": str(manifest_path),
            "sha256": _sha256_file(manifest_path),
            **manifest,
        },
    }
    properties = [
        f"-Dlizzie.acceptance.engine={engine}",
        f"-Dlizzie.acceptance.model={model}",
        f"-Dlizzie.acceptance.engine.config={config}",
        f"-Dlizzie.acceptance.engine.manifest={manifest_path}",
    ]
    return identity, properties


def build_plan(
    args: argparse.Namespace, output: Path, xvfb: str, maven: str
) -> RunPlan:
    selected = tuple(args.scenario or DEFAULT_SCENARIOS)
    engine_args: list[str] = []
    inputs: dict[str, object] = {}
    if "real-cpu-engine" in selected:
        inputs, engine_args = _validated_d4_inputs(args)
    elif "quick-analysis" in selected:
        if not args.engine or not args.model:
            raise RuntimeError("quick-analysis requires --engine and --model (real local KataGo assets)")
        engine, model = args.engine.resolve(), args.model.resolve()
        if not engine.is_file() or not os.access(engine, os.X_OK) or not model.is_file():
            raise RuntimeError("Engine must be executable and model must be an existing file")
        inputs.update(engine=str(engine), model=str(model))
        engine_args = [
            f"-Dlizzie.acceptance.engine={engine}",
            f"-Dlizzie.acceptance.model={model}",
        ]
    cases = [case for name in selected for case in SCENARIOS[name]]
    command = (
        xvfb,
        "-a",
        "-e",
        str(output / "xvfb.log"),
        "-s",
        "-screen 0 1600x1000x24 -nolisten tcp",
        maven,
        "-B",
        "-Dfmt.skip=true",
        "-Djava.awt.headless=false",
        "-Dlizzie.desktop.required=true",
        f"-Dlizzie.desktop.evidence.dir={output / 'probes'}",
        f"-Dsurefire.reportsDirectory={output / 'surefire-reports'}",
        f"-Dtest={_test_selector(cases)}",
        *engine_args,
        "test",
    )
    return RunPlan(
        selected=selected,
        command=command,
        inputs=inputs,
        scenario_results=_initial_scenario_results(selected),
    )




def _single_probe_result(output: Path, prefix: str, scenario: str) -> Path:
    matches = sorted((output / "probes").glob(f"{prefix}-*/result.txt"))
    if len(matches) != 1:
        raise RuntimeError(
            f"{scenario} required exactly one {prefix} result, found {len(matches)}"
        )
    return matches[0]


def _parse_key_value_result(path: Path) -> dict[str, str]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise RuntimeError(f"cannot read probe result {path}: {error}") from error
    result: dict[str, str] = {}
    for line in lines:
        if "=" not in line:
            raise RuntimeError(f"malformed probe result line in {path}: {line!r}")
        key, value = line.split("=", 1)
        if not key or not value or key in result:
            raise RuntimeError(f"invalid or duplicate probe result key in {path}: {key!r}")
        result[key] = value
    return result


def _require_result_fields(result: dict[str, str], required: set[str], label: str) -> None:
    missing = required - set(result)
    if missing:
        raise RuntimeError(f"{label} result is missing keys: {sorted(missing)}")


def _require_regular_evidence(result: dict[str, str], keys: set[str], label: str) -> None:
    for key in keys:
        path = Path(result[key])
        if not path.is_file():
            raise RuntimeError(f"{label} evidence {key} is missing: {path}")


def _collect_d3_evidence(output: Path) -> list[dict[str, object]]:
    session_one_path = _single_probe_result(output, "d3-sgf-ui-session-1", "sgf-ui")
    session_two_path = _single_probe_result(output, "d3-sgf-ui-session-2", "sgf-ui")
    session_one = _parse_key_value_result(session_one_path)
    session_two = _parse_key_value_result(session_two_path)
    common = {"scenario", "source.sha256", "peer.kind", "platform.os", "platform.arch", "runtime.java"}
    session_one_booleans = {
        "open.menu", "open.chooser", "history.adopted", "rules.confirmed",
        "rules.confirmed-before-position", "position.confirmed", "navigation.keys",
        "navigation.focus", "analysis.current-node", "analysis.positive-visits",
        "analysis.quiet-400ms", "save.menu", "save.chooser", "semantic.before-save",
        "overwrite.cancel", "protected.unchanged", "stop", "quit", "cleanup.process",
        "cleanup.readers", "cleanup.sgf",
    }
    session_two_booleans = {
        "open.menu", "open.chooser", "history.adopted", "semantic.tree", "semantic.metadata",
        "semantic.setup", "semantic.comments", "semantic.markup", "semantic.rules",
        "reopen.current-node", "quit", "cleanup.process", "cleanup.readers", "cleanup.sgf",
    }
    session_one_files = {
        "fixture.path", "output.path", "protected.path", "evidence.commands",
        "evidence.peer-state", "evidence.loaded-sgf", "evidence.stopped", "evidence.quit",
        "evidence.peer-complete", "evidence.stdout", "evidence.stderr", "evidence.app-log",
        "evidence.phases", "evidence.lifecycle",
    }
    session_two_files = {
        "fixture.path", "output.path", "protected.path", "evidence.stdout", "evidence.stderr",
        "evidence.app-log", "evidence.phases", "evidence.lifecycle",
    }
    _require_result_fields(
        session_one, common | session_one_booleans | session_one_files | {"evidence.staged-sgf"},
        "sgf-ui session 1",
    )
    _require_result_fields(session_two, common | session_two_booleans | session_two_files, "sgf-ui session 2")
    if session_one["scenario"] != "d3-sgf-ui-session-1" or session_one["peer.kind"] != "controlled-java":
        raise RuntimeError("sgf-ui session 1 identity is invalid")
    if session_two["scenario"] != "d3-sgf-ui-session-2" or session_two["peer.kind"] != "none":
        raise RuntimeError("sgf-ui session 2 identity is invalid")
    for label, result, boolean_keys, file_keys in (
        ("sgf-ui session 1", session_one, session_one_booleans, session_one_files),
        ("sgf-ui session 2", session_two, session_two_booleans, session_two_files),
    ):
        rejected = sorted(key for key in boolean_keys if result[key] != "true")
        if rejected:
            raise RuntimeError(f"{label} has non-affirmative outcomes: {rejected}")
        _require_regular_evidence(result, file_keys, label)
    staged = Path(session_one["evidence.staged-sgf"])
    if staged.exists():
        raise RuntimeError(f"sgf-ui staged SGF survived cleanup: {staged}")
    return [
        {"path": str(session_one_path), "observed": session_one},
        {"path": str(session_two_path), "observed": session_two},
    ]


def _collect_d4_evidence(output: Path, inputs: dict[str, object]) -> list[dict[str, object]]:
    result_path = _single_probe_result(output, "real-cpu-engine", "real-cpu-engine")
    result = _load_json(result_path, "real-cpu-engine result")
    expected_keys = {
        "schemaVersion", "scenario", "status", "failure", "peer", "source", "platform",
        "manifest", "assets", "fixture", "node", "rules", "position", "analysis", "phasesMs",
        "stop", "quit", "cleanup", "evidence",
    }
    _require_exact_keys(result, "real-cpu-engine result", expected_keys)
    peer = _require_object(result["peer"], "real-cpu-engine peer")
    manifest = _require_object(result["manifest"], "real-cpu-engine manifest")
    cleanup = _require_object(result["cleanup"], "real-cpu-engine cleanup")
    evidence = _require_object(result["evidence"], "real-cpu-engine evidence")
    d4_manifest = _require_object(inputs.get("d4_manifest"), "validated D4 manifest")
    if result.get("schemaVersion") != 1 or result.get("scenario") != "real-cpu-engine":
        raise RuntimeError("real-cpu-engine result identity is invalid")
    if result.get("status") != "PASS" or result.get("failure") is not None:
        raise RuntimeError("real-cpu-engine child result did not PASS")
    if peer.get("kind") != "real-katago-cpu":
        raise RuntimeError("real-cpu-engine peer identity is invalid")
    if manifest.get("path") != d4_manifest.get("path") or manifest.get("sha256") != d4_manifest.get("sha256"):
        raise RuntimeError("real-cpu-engine manifest identity differs from preflight")
    if any(cleanup.get(key) is not True for key in ("process", "readers", "stagedSgf")):
        raise RuntimeError("real-cpu-engine cleanup is incomplete")
    for key in ("result", "stdout", "stderr", "appLog", "phases"):
        value = evidence.get(key)
        if not isinstance(value, str) or not Path(value).is_file():
            raise RuntimeError(f"real-cpu-engine evidence {key} is missing: {value}")
    staged = evidence.get("stagedSgfs")
    if not isinstance(staged, list) or not staged or any(Path(str(path)).exists() for path in staged):
        raise RuntimeError("real-cpu-engine staged SGF cleanup evidence is invalid")
    return [{"path": str(result_path), "observed": result}]


def collect_scenario_evidence(
    output: Path,
    selected: tuple[str, ...],
    scenario_results: dict[str, dict[str, object]],
    inputs: dict[str, object],
) -> None:
    if "sgf-ui" in selected:
        scenario_results["sgf-ui"]["evidence_records"] = _collect_d3_evidence(output)
    if "real-cpu-engine" in selected:
        scenario_results["real-cpu-engine"]["evidence_records"] = _collect_d4_evidence(output, inputs)


def run(args: argparse.Namespace) -> int:
    started = time.monotonic()
    selected = tuple(args.scenario or DEFAULT_SCENARIOS)
    if args.output:
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=False)
    else:
        parent = REPO_ROOT / "target" / "acceptance"
        parent.mkdir(parents=True, exist_ok=True)
        output = Path(tempfile.mkdtemp(prefix="run-", dir=parent))
    record = {
        "result": "BLOCKED",
        "platform": platform.platform(),
        "surface": "Linux/Xvfb",
        "started_at": datetime.now(timezone.utc).isoformat(),
        "scenarios": selected,
        "scenario_results": _initial_scenario_results(selected),
        "source": str(REPO_ROOT),
        "git_sha": git_output("rev-parse", "HEAD"),
        "working_tree": git_output("status", "--porcelain"),
        "evidence": str(output),
        "cleanup": {"process_group_started": False, "process_group_terminated": None},
    }
    child = None
    try:
        if platform.system() != "Linux":
            raise RuntimeError("This isolated runner requires Linux/Xvfb; use the Windows candidate session tool for native Windows acceptance")
        xvfb = shutil.which("xvfb-run")
        if not xvfb or not shutil.which("Xvfb") or not shutil.which("xauth"):
            raise RuntimeError("Install xvfb and xauth before running acceptance")
        maven = resolve_maven()
        major, java_details = java_major_version(maven)
        record["java"] = java_details
        if major != 21:
            raise RuntimeError(f"Maven must use JDK 21, observed {major}")
        plan = build_plan(args, output, xvfb, maven)
        selected = plan.selected
        record.update(plan.inputs)
        record["scenario_results"] = plan.scenario_results
        cases = [case for name in selected for case in SCENARIOS[name]]
        command = list(plan.command)
        record["command"] = command
        environment = os.environ.copy()
        # The wrapper creates an owned X server; never inherit the user's WSLg surface.
        environment.pop("DISPLAY", None)
        environment.pop("WAYLAND_DISPLAY", None)
        with (output / "maven.log").open("w", encoding="utf-8") as log:
            child = subprocess.Popen(
                command,
                cwd=REPO_ROOT,
                env=environment,
                stdout=log,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
            record["result"] = "FAIL"
            for details in record["scenario_results"].values():
                details["status"] = "FAIL"
            record["cleanup"] = {
                "process_group_started": True,
                "process_group_terminated": False,
            }
            record["process_group"] = child.pid
            try:
                code = child.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired as error:
                raise RuntimeError(
                    f"Acceptance exceeded {args.timeout}s; owned process group terminated"
                ) from error
        record["exit_code"] = code
        required = [("featurecat.lizzie.gui." + name, method) for name, method in cases]
        try:
            junit = collect_junit_summary(output, required)
        except RequiredJunitExecutionError as error:
            record["junit"] = asdict(error.summary)
            raise
        record["junit"] = asdict(junit)
        if code != 0 or junit.failures or junit.errors or junit.skipped:
            raise RuntimeError("Required acceptance did not pass; inspect maven.log and probe evidence")
        collect_scenario_evidence(output, selected, record["scenario_results"], plan.inputs)
        record["result"] = "PASS"
        for details in record["scenario_results"].values():
            details["status"] = "PASS"
    except (OSError, RuntimeError) as error:
        record["error"] = str(error)
    finally:
        if child is not None:
            # Includes Xvfb and a JVM left alive by a failed/terminated Maven invocation.
            try:
                os.killpg(child.pid, signal.SIGTERM)
                time.sleep(0.2)
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()
            record["cleanup"]["process_group_terminated"] = True
        record["completed_at"] = datetime.now(timezone.utc).isoformat()
        record["duration_seconds"] = round(time.monotonic() - started, 3)
        (output / "acceptance.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{record['result']} Linux/Xvfb {','.join(selected)} {record['duration_seconds']:.1f}s")
        if "error" in record:
            print(record["error"])
        print(f"Evidence: {output / 'acceptance.json'}")
    return 0 if record["result"] == "PASS" else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--scenario",
        choices=SCENARIOS,
        action="append",
        help="Repeat to select scenarios; default: search, settings, quick-analysis",
    )
    parser.add_argument(
        "--engine",
        type=Path,
        help="Real Linux KataGo executable for quick-analysis or real-cpu-engine",
    )
    parser.add_argument(
        "--model", type=Path, help="Real KataGo model for quick-analysis or real-cpu-engine"
    )
    parser.add_argument(
        "--engine-config", type=Path, help="default_gtp.cfg for real-cpu-engine"
    )
    parser.add_argument(
        "--engine-manifest", type=Path, help="Prepared asset manifest for real-cpu-engine"
    )
    parser.add_argument(
        "--output", type=Path, help="Fresh evidence directory; existing paths are refused"
    )
    parser.add_argument(
        "--timeout", type=int, default=600, help="Total Maven/display deadline in seconds"
    )
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
