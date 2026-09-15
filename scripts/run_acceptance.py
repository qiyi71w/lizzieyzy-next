#!/usr/bin/env python3
"""Run bounded local desktop acceptance on a private Linux Xvfb display."""
from __future__ import annotations

import argparse
from dataclasses import asdict
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import subprocess
import tempfile
import time

from run_local_ci import REPO_ROOT, collect_junit_summary, git_output, java_major_version, resolve_maven

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
}


def run(args: argparse.Namespace) -> int:
    started = time.monotonic()
    selected = args.scenario or list(SCENARIOS)
    if args.output:
        output = args.output.resolve()
        output.mkdir(parents=True, exist_ok=False)
    else:
        parent = REPO_ROOT / "target" / "acceptance"
        parent.mkdir(parents=True, exist_ok=True)
        output = Path(tempfile.mkdtemp(prefix="run-", dir=parent))
    record = {
        "result": "BLOCKED", "platform": platform.platform(), "surface": "Linux/Xvfb",
        "started_at": datetime.now(timezone.utc).isoformat(), "scenarios": selected,
        "source": str(REPO_ROOT), "git_sha": git_output("rev-parse", "HEAD"),
        "working_tree": git_output("status", "--porcelain"), "evidence": str(output),
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
        engine_args = []
        if "quick-analysis" in selected:
            if not args.engine or not args.model:
                raise RuntimeError("quick-analysis requires --engine and --model (real local KataGo assets)")
            engine, model = args.engine.resolve(), args.model.resolve()
            if not engine.is_file() or not os.access(engine, os.X_OK) or not model.is_file():
                raise RuntimeError("Engine must be executable and model must be an existing file")
            record["engine"] = str(engine)
            record["model"] = str(model)
            engine_args = [f"-Dlizzie.acceptance.engine={engine}", f"-Dlizzie.acceptance.model={model}"]
        cases = [case for name in selected for case in SCENARIOS[name]]
        tests = ",".join(dict.fromkeys(name for name, _ in cases))
        command = [xvfb, "-a", "-e", str(output / "xvfb.log"), "-s", "-screen 0 1600x1000x24 -nolisten tcp",
                   maven, "-B", "-Dfmt.skip=true", "-Djava.awt.headless=false",
                   "-Dlizzie.desktop.required=true", f"-Dlizzie.desktop.evidence.dir={output / 'probes'}",
                   f"-Dsurefire.reportsDirectory={output / 'surefire-reports'}", f"-Dtest={tests}",
                   *engine_args, "test"]
        record["command"] = command
        environment = os.environ.copy()
        # The wrapper creates an owned X server; never inherit the user's WSLg surface.
        environment.pop("DISPLAY", None)
        environment.pop("WAYLAND_DISPLAY", None)
        record["result"] = "FAIL"
        with (output / "maven.log").open("w", encoding="utf-8") as log:
            child = subprocess.Popen(command, cwd=REPO_ROOT, env=environment,
                                     stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            record["process_group"] = child.pid
            try:
                code = child.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired as error:
                raise RuntimeError(f"Acceptance exceeded {args.timeout}s; owned process group terminated") from error
        record["exit_code"] = code
        required = [("featurecat.lizzie.gui." + name, method) for name, method in cases]
        junit = collect_junit_summary(output, required)
        record["junit"] = asdict(junit)
        if code != 0 or junit.failures or junit.errors or junit.skipped:
            raise RuntimeError("Required acceptance did not pass; inspect maven.log and probe evidence")
        record["result"] = "PASS"
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
        record["duration_seconds"] = round(time.monotonic() - started, 3)
        (output / "acceptance.json").write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"{record['result']} Linux/Xvfb {','.join(selected)} {record['duration_seconds']:.1f}s")
        if "error" in record:
            print(record["error"])
        print(f"Evidence: {output / 'acceptance.json'}")
    return 0 if record["result"] == "PASS" else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", choices=SCENARIOS, action="append", help="Repeat to select scenarios; default: all")
    parser.add_argument("--engine", type=Path, help="Real Linux KataGo executable for quick-analysis")
    parser.add_argument("--model", type=Path, help="Real KataGo model for quick-analysis")
    parser.add_argument("--output", type=Path, help="Fresh evidence directory; existing paths are refused")
    parser.add_argument("--timeout", type=int, default=600, help="Total Maven/display deadline in seconds")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
