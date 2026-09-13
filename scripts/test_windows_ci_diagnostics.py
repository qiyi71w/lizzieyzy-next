#!/usr/bin/env python3
"""Exercise the Windows CI supervisor against real owned process trees."""

import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


@unittest.skipUnless(os.name == "nt", "requires native Windows process ownership")
class WindowsCiDiagnosticsTest(unittest.TestCase):
    def setUp(self):
        self.pwsh = shutil.which("pwsh")
        if not self.pwsh:
            self.fail("PowerShell 7 is required for Windows CI supervision checks")
        self.temporary = tempfile.TemporaryDirectory(prefix="ci supervisor ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.output = self.root / "evidence"
        self.driver = self.root / "invoke.ps1"
        self.driver.write_text(
            "$ErrorActionPreference = 'Stop'\n"
            "$options = $env:CI_SUPERVISOR_TEST_OPTIONS | ConvertFrom-Json -AsHashtable\n"
            "& $env:CI_SUPERVISOR_TEST_SCRIPT @options\n"
            "exit $LASTEXITCODE\n",
            encoding="utf-8",
        )

    def invoke(self, code, *, timeout=15, stall=2, deadline=None, arguments=()):
        environment = os.environ.copy()
        environment.pop("LIZZIE_CI_DEADLINE_UNIX_SECONDS", None)
        if deadline is not None:
            environment["LIZZIE_CI_DEADLINE_UNIX_SECONDS"] = str(deadline)
        environment["CI_SUPERVISOR_TEST_SCRIPT"] = str(
            Path(__file__).resolve().with_name("run_windows_ci_diagnostics.ps1")
        )
        environment["CI_SUPERVISOR_TEST_OPTIONS"] = json.dumps({
            "Executable": sys.executable,
            "CommandArguments": ["-c", code, *arguments],
            "WorkingDirectory": str(self.root),
            "OutputDirectory": str(self.output),
            "TimeoutSeconds": timeout,
            "StallSeconds": stall,
            "DumpIntervalSeconds": 1,
            "DumpTimeoutSeconds": 1,
        })
        return subprocess.run(
            [self.pwsh, "-NoLogo", "-NoProfile", "-NonInteractive", "-File", str(self.driver)],
            env=environment, capture_output=True, text=True, timeout=timeout + 30,
        )

    def summary(self):
        return json.loads((self.output / "summary.json").read_text(encoding="utf-8-sig"))

    def assert_process_stopped(self, pid):
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.argtypes = [ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
        kernel.OpenProcess.restype = ctypes.c_void_p
        kernel.WaitForSingleObject.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
        kernel.CloseHandle.argtypes = [ctypes.c_void_p]
        handle = kernel.OpenProcess(0x00100000, False, pid)
        if not handle:
            self.assertEqual(87, ctypes.get_last_error(), "unable to verify child process exit")
            return
        try:
            self.assertEqual(0, kernel.WaitForSingleObject(handle, 0), "owned child survived cleanup")
        finally:
            kernel.CloseHandle(handle)

    def test_expired_job_deadline_does_not_start_work(self):
        marker = self.root / "started"
        result = self.invoke(
            f"from pathlib import Path; Path({str(marker)!r}).touch()", deadline=1,
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(marker.exists(), "expired job deadline still started work")
        self.assertNotEqual("completed", self.summary()["status"])

    def test_preserves_failure_arguments_and_drains_both_output_streams(self):
        arguments = ["argument with spaces", "", 'quoted"value', "trailing\\", 'slash\\"quote', "围棋"]
        result = self.invoke(
            "import json,sys; print(json.dumps(sys.argv[1:])); print('o'*200000); "
            "sys.stderr.write('e'*200000+'\\nerror-tail\\n'); print('output-tail'); sys.exit(7)",
            arguments=arguments,
        )
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        self.assertEqual(7, self.summary()["exitCode"])
        stdout = (self.output / "console.stdout.log").read_text()
        self.assertEqual(arguments, json.loads(stdout.splitlines()[0]))
        self.assertIn("output-tail", stdout)
        self.assertIn("error-tail", (self.output / "console.stderr.log").read_text())

    def test_quiet_work_gets_two_snapshots_without_being_failed(self):
        result = self.invoke("import time; time.sleep(7)", stall=1)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("completed", self.summary()["status"])
        self.assertEqual(2, len(list(self.output.glob("snapshot-*.json"))))

    def test_nested_active_plan_ignores_console_noise_for_stall_detection(self):
        result = self.invoke(
            "import json,os,pathlib,time; "
            "p=pathlib.Path(os.environ['LIZZIE_CI_EVIDENCE_DIR'])/f'junit-{os.getpid()}.jsonl'; "
            "events=['PLAN_STARTED','PLAN_STARTED','PLAN_FINISHED']; "
            "p.write_text(''.join(json.dumps({'event':e,'pid':os.getpid()})+'\\n' "
            "for e in events),encoding='utf-8'); "
            "[(print('output while outer plan is blocked',flush=True),time.sleep(.1)) "
            "for _ in range(70)]",
            stall=1,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        snapshots = sorted(self.output.glob("snapshot-*.json"))
        self.assertEqual(2, len(snapshots), result.stdout + result.stderr)
        for path in snapshots:
            self.assertEqual("stall", json.loads(path.read_text(encoding="utf-8-sig"))["reason"])

    def test_timeout_keeps_evidence_and_terminates_owned_descendants(self):
        marker = self.root / "child.pid"
        result = self.invoke(
            "import subprocess,sys,time,pathlib; "
            "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(120)']); "
            f"pathlib.Path({str(marker)!r}).write_text(str(child.pid)); "
            "time.sleep(120)",
            timeout=8, stall=1,
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("timed_out", self.summary()["status"])
        self.assertEqual(2, len(list(self.output.glob("snapshot-*.json"))))
        self.assert_process_stopped(int(marker.read_text()))

    def test_parent_exit_cannot_hide_a_stranded_descendant(self):
        marker = self.root / "orphan.pid"
        result = self.invoke(
            "import subprocess,sys,pathlib; "
            "child=subprocess.Popen([sys.executable,'-c','import time; time.sleep(120)']); "
            f"pathlib.Path({str(marker)!r}).write_text(str(child.pid))"
        )
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotEqual("completed", self.summary()["status"])
        self.assert_process_stopped(int(marker.read_text()))


if __name__ == "__main__":
    unittest.main()
