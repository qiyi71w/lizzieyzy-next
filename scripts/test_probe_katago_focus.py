#!/usr/bin/env python3

import io
import json
from pathlib import Path
import queue
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from probe_katago_focus import GtpProbe, parse_analysis, probe, sha256


class FocusProbeTest(unittest.TestCase):
    def fake_session(self, lines):
        session = object.__new__(GtpProbe)
        session.timeout = 0.01
        session.log = io.StringIO()
        session.lines = queue.Queue()
        for line in lines:
            session.lines.put(line)
        session.command = lambda command: None
        return session

    def test_root_visits_are_not_sum_of_candidates(self):
        self.assertEqual(
            (15, {"D4": 100, "Q16": 40}),
            parse_analysis("info move D4 visits 100 info move Q16 visits 40 rootInfo visits 15"),
        )

    def test_non_analysis_is_ignored(self):
        self.assertIsNone(parse_analysis("=14 KataGo"))

    def test_missing_candidates_fail(self):
        with self.assertRaises(ValueError):
            parse_analysis("rootInfo visits 30")

    def test_waits_for_focused_candidate_and_normal_tree_progress(self):
        session = self.fake_session([
            "info move D4 visits 0 rootInfo visits 12",
            "info move D4 visits 5 rootInfo visits 23",
        ])
        self.assertEqual(23, session.analysis("focus", 10, ("D4",))["rootVisits"])

    def test_tree_reset_is_failure_even_if_later_output_recovers(self):
        session = self.fake_session([
            "info move D4 visits 5 rootInfo visits 5",
            "info move D4 visits 50 rootInfo visits 100",
        ])
        with self.assertRaisesRegex(RuntimeError, "search tree reset"):
            session.analysis("focus", 40, ("D4",))

    def test_exit_before_result_fails(self):
        with self.assertRaisesRegex(RuntimeError, "exited"):
            self.fake_session([None]).analysis("focus", 0, ())

    def test_no_output_times_out(self):
        with self.assertRaises(TimeoutError):
            self.fake_session([]).analysis("focus", 0, ())

    def test_never_overwrites_evidence(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)
            engine = path / "engine"
            model = path / "model"
            engine.write_bytes(b"engine")
            model.write_bytes(b"model")
            with self.assertRaises(FileExistsError):
                probe(engine, model, path)

    def test_rejects_binary_from_another_commit(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)
            engine = path / "engine"
            model = path / "model"
            engine.write_bytes(b"engine")
            model.write_bytes(b"model")
            with patch("probe_katago_focus.subprocess.run") as command:
                command.return_value.stdout = "KataGo v1.18.2\nGit revision: old\n"
                with self.assertRaisesRegex(ValueError, "source commit"):
                    probe(engine, model, path / "evidence")
            self.assertIn('"status": "FAIL"', (path / "evidence/result.json").read_text())

    def test_version_check_uses_explicit_deadline_without_retrying_timeout(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root)
            engine, model = path / "engine", path / "model"
            engine.write_bytes(b"engine")
            model.write_bytes(b"model")
            with patch("probe_katago_focus.subprocess.run") as command:
                command.side_effect = subprocess.TimeoutExpired("version", 87)
                with self.assertRaises(subprocess.TimeoutExpired):
                    probe(engine, model, path / "evidence", timeout=87)
                self.assertEqual(1, command.call_count)
                self.assertEqual(87, command.call_args.kwargs["timeout"])
            result = json.loads((path / "evidence/result.json").read_text())
            self.assertEqual("FAIL", result["status"])
            self.assertEqual([], result["steps"])
            self.assertEqual(87, result["operationTimeoutSeconds"])
            self.assertGreaterEqual(result["versionCheckSeconds"], 0)

    def test_invalid_deadline_cannot_disable_timeouts(self):
        for timeout in (0, -1, 601, float("nan"), float("inf")):
            with self.assertRaisesRegex(ValueError, "timeout"):
                probe(Path("unused-engine"), Path("unused-model"), Path("unused-evidence"), timeout)

    def test_digest_is_of_actual_bytes(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / "engine"
            path.write_bytes(b"abc")
            self.assertEqual(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                sha256(path),
            )


if __name__ == "__main__":
    unittest.main()
