#!/usr/bin/env python3
"""Exercise same-tree GTP focus against a real, explicitly selected KataGo process."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time


SOURCE_COMMIT = "47aadc08518b3e121f22539796c911002f699584"
ROOT_VISITS = re.compile(r"\brootInfo visits (\d+)\b")
CANDIDATE_VISITS = re.compile(r"\binfo move ([A-T][0-9]+|pass) visits (\d+)\b")


def sha256(path: Path) -> str:
    with path.open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def parse_analysis(line: str) -> tuple[int, dict[str, int]] | None:
    root = ROOT_VISITS.search(line)
    if root is None:
        return None
    moves = {move: int(visits) for move, visits in CANDIDATE_VISITS.findall(line)}
    if not moves:
        raise ValueError("rootInfo without candidate visit statistics")
    return int(root.group(1)), moves


class GtpProbe:
    def __init__(self, executable: Path, model: Path, evidence: Path, timeout: float):
        self.timeout = timeout
        self.sequence = 0
        self.lines: queue.Queue[str | None] = queue.Queue()
        self.log = (evidence / "protocol.log").open("w", encoding="utf-8")
        config = evidence / "gtp.cfg"
        config.write_text(
            "logAllGTPCommunication = false\nlogSearchInfo = false\nlogToStderr = true\n"
            "numSearchThreads = 2\nrules = chinese\nponderingEnabled = false\n",
            encoding="utf-8",
        )
        self.stderr = (evidence / "stderr.log").open("w", encoding="utf-8")
        self.process = subprocess.Popen(
            [str(executable), "gtp", "-model", str(model), "-config", str(config)],
            cwd=evidence,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=self.stderr,
            text=True,
            encoding="utf-8",
        )
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self) -> None:
        try:
            assert self.process.stdout is not None
            for line in self.process.stdout:
                self.lines.put(line.rstrip("\r\n"))
        finally:
            self.lines.put(None)

    def _next(self, deadline: float) -> str:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("KataGo GTP response timed out")
        try:
            line = self.lines.get(timeout=remaining)
        except queue.Empty as error:
            raise TimeoutError("KataGo GTP response timed out") from error
        if line is None:
            raise RuntimeError("KataGo exited before the expected GTP response")
        self.log.write("< " + line + "\n")
        self.log.flush()
        return line

    def command(self, command: str) -> None:
        self.sequence += 1
        numbered = f"{self.sequence} {command}"
        self.log.write("> " + numbered + "\n")
        self.log.flush()
        assert self.process.stdin is not None
        self.process.stdin.write(numbered + "\n")
        self.process.stdin.flush()
        deadline = time.monotonic() + self.timeout
        while True:
            line = self._next(deadline)
            if re.match(rf"^\?{self.sequence}(?:\s|$)", line):
                raise RuntimeError("KataGo rejected " + command + ": " + line)
            if re.match(rf"^={self.sequence}(?:\s|$)", line):
                return

    def analysis(self, command: str, previous: int, watched: tuple[str, ...]) -> dict:
        self.command(command)
        deadline = time.monotonic() + self.timeout
        while True:
            parsed = parse_analysis(self._next(deadline))
            if parsed is None:
                continue
            root, moves = parsed
            if root < previous:
                raise RuntimeError(f"search tree reset: root visits {previous} -> {root}")
            if root >= previous + 10 and all(moves.get(move, 0) > 0 for move in watched):
                return {"command": command, "rootVisits": root, "candidateVisits": moves}

    def close(self) -> None:
        try:
            if self.process.poll() is None:
                self.process.terminate()
                try:
                    self.process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
            self.reader.join(timeout=5)
            if self.process.stdin:
                self.process.stdin.close()
            if self.process.stdout:
                self.process.stdout.close()
        finally:
            self.log.close()
            self.stderr.close()


def probe(executable: Path, model: Path, evidence: Path, timeout: float = 120) -> dict:
    executable, model, evidence = executable.resolve(), model.resolve(), evidence.resolve()
    if not executable.is_file() or not model.is_file():
        raise ValueError("explicit executable and model files are required")
    evidence.mkdir(parents=True, exist_ok=False)
    result = {"status": "FAIL", "sourceCommit": SOURCE_COMMIT, "steps": []}
    session = None
    try:
        version = subprocess.run(
            [str(executable), "version"], check=True, capture_output=True, text=True, timeout=30
        ).stdout
        if f"Git revision: {SOURCE_COMMIT}" not in version:
            raise ValueError("binary does not report the pinned upstream source commit")
        result.update(version=version, executableSha256=sha256(executable), modelSha256=sha256(model))
        session = GtpProbe(executable, model, evidence, timeout)
        for command in ("boardsize 19", "komi 7.5", "play B D4", "play W Q16"):
            session.command(command)
        base = "kata-analyze B interval 10 rootInfo true minmoves 20"
        previous = 0
        for suffix, watched in (
            ("", ()),
            (" focus Q4 0.5", ("Q4",)),
            (" focus Q4,D16 0.5", ("Q4", "D16")),
            (" focus D16 0.5", ("D16",)),
            ("", ()),
        ):
            step = session.analysis(base + suffix, previous, watched)
            result["steps"].append(step)
            previous = step["rootVisits"]
        session.command("name")
        session.command("quit")
        if session.process.wait(timeout=10) != 0:
            raise RuntimeError("KataGo did not exit cleanly")
        result["status"] = "PASS"
        return result
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        if session is not None:
            session.close()
        (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=120)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 600:
        parser.error("timeout must be between 1 and 600 seconds")
    result = probe(args.engine, args.model, args.evidence, args.timeout)
    print(json.dumps({"status": result["status"], "rootVisits": [s["rootVisits"] for s in result["steps"]]}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
