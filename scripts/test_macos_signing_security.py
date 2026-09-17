#!/usr/bin/env python3
"""Windows-runnable static security tests for the macOS signing transaction."""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[1]
SIGN_SCRIPT = ROOT / "scripts" / "sign_macos_release.sh"


def find_bash() -> str | None:
    # Windows may expose the WSL launcher as bash.exe even when no usable Linux
    # distribution exists. Keep the executable macOS cleanup fixture on POSIX;
    # the static signing-security checks remain fully Windows-runnable.
    if os.name == "nt":
        return None
    discovered = shutil.which("bash")
    if discovered:
        return discovered
    for candidate in (Path("/bin/bash"), Path("/usr/bin/bash")):
        if candidate.is_file():
            return str(candidate)
    return None


BASH = find_bash()


def write_executable(path: Path, content: str) -> None:
    path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def write_fake_security(path: Path) -> None:
    write_executable(
        path,
        r"""
        #!/usr/bin/env python3
        import json
        import os
        from pathlib import Path
        import stat
        import sys

        args = sys.argv[1:]
        command = args[0] if args else ""
        record = {"args": args}

        if command == "import" and len(args) >= 2:
            certificate = Path(args[1])
            explicit_pkcs12 = any(
                args[index] == "-f"
                and index + 1 < len(args)
                and args[index + 1].lower() == "pkcs12"
                for index in range(len(args))
            )
            forced_failure = os.environ.get("FAKE_SECURITY_FAIL_IMPORT") == "1"
            accepted = (
                certificate.suffix.lower() == ".p12" or explicit_pkcs12
            ) and not forced_failure
            record.update(
                {
                    "accepted": accepted,
                    "forced_failure": forced_failure,
                    "explicit_pkcs12": explicit_pkcs12,
                    "certificate": str(certificate),
                    "certificate_mode": (
                        stat.S_IMODE(certificate.stat().st_mode)
                        if certificate.exists()
                        else None
                    ),
                    "parent_mode": (
                        stat.S_IMODE(certificate.parent.stat().st_mode)
                        if certificate.parent.exists()
                        else None
                    ),
                }
            )

        log_path = os.environ.get("SECURITY_LOG")
        if log_path:
            with open(log_path, "a", encoding="utf-8") as log:
                log.write(json.dumps(record) + "\n")

        if command == "list-keychains" and "-s" not in args:
            for keychain in os.environ.get("FAKE_ORIGINAL_KEYCHAINS", "").splitlines():
                if keychain:
                    print(f'"{keychain}"')
        if command == "import":
            sys.exit(0 if record["accepted"] else 65)
        sys.exit(0)
        """,
    )


class MacOSSigningSecurityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.script = SIGN_SCRIPT.read_text(encoding="utf-8")

    def test_cleanup_trap_precedes_all_keychain_and_certificate_creation(self) -> None:
        trap = self.script.index("trap cleanup EXIT")
        self.assertLess(trap, self.script.index("security create-keychain"))
        self.assertLess(trap, self.script.index('cert_dir="$(mktemp'))
        self.assertIn("trap 'exit 129' HUP", self.script)
        self.assertIn("trap 'exit 130' INT", self.script)
        self.assertIn("trap 'exit 143' TERM", self.script)

    def test_keychain_and_sensitive_files_are_unique_and_restrictive(self) -> None:
        self.assertNotIn('keychain="lizzieyzy-sign.keychain-db"', self.script)
        self.assertNotIn("mktemp -t", self.script)
        self.assertIn("lizzieyzy-keychain.XXXXXXXX", self.script)
        self.assertIn('keychain="$keychain_dir/signing.keychain-db"', self.script)
        self.assertIn('chmod 700 "$keychain_dir"', self.script)
        self.assertIn(
            'cert_dir="$(mktemp -d "$temp_root/lizzieyzy-cert.XXXXXXXX")"',
            self.script,
        )
        self.assertIn('chmod 700 "$cert_dir"', self.script)
        self.assertIn('cert_path="$cert_dir/certificate.p12"', self.script)
        self.assertIn('chmod 600 "$cert_path"', self.script)
        self.assertIn(
            'security import "$cert_path" -f pkcs12',
            self.script,
        )

    def test_partial_secrets_fail_closed_and_all_absent_explicitly_skip(self) -> None:
        for name in (
            "APPLE_CERT_P12",
            "APPLE_ID",
            "APPLE_APP_PASSWORD",
            "APPLE_TEAM_ID",
        ):
            self.assertIn(name, self.script)
        self.assertIn('if [[ "$configured_secret_count" -eq 0 ]]', self.script)
        self.assertIn("credentials are fully absent; skipping", self.script)
        self.assertIn('if [[ "$configured_secret_count" -ne', self.script)
        self.assertIn("credentials are only partially configured", self.script)

    def test_cleanup_removes_every_sensitive_resource_on_early_failure(self) -> None:
        cleanup_start = self.script.index("cleanup() {")
        cleanup_end = self.script.index("trap cleanup EXIT", cleanup_start)
        cleanup = self.script[cleanup_start:cleanup_end]
        self.assertIn("set +u", cleanup)
        self.assertIn('rm -f -- "$cert_path"', cleanup)
        self.assertIn('rm -rf -- "$cert_dir"', cleanup)
        self.assertIn('for path in "${temporary_paths[@]}"', cleanup)
        self.assertIn('security delete-keychain "$keychain"', cleanup)
        self.assertIn('rm -rf -- "$keychain_dir"', cleanup)
        self.assertIn('rm -rf -- "$work_dir"', cleanup)
        self.assertIn('hdiutil detach "$mounted_target" -force', cleanup)

    def test_gatekeeper_failure_keeps_original_dmg_and_runs_cleanup(self) -> None:
        assessment = self.script.index("if ! spctl --assess")
        replacement = self.script.index('mv "$signed_dmg" "$dmg"', assessment)
        guarded_tail = self.script[assessment:replacement]
        self.assertNotIn("|| true", guarded_tail)
        self.assertIn("refusing to replace the original DMG", guarded_tail)
        self.assertIn("exit 1", guarded_tail)
        self.assertLess(assessment, replacement)

        cleanup_start = self.script.index("cleanup() {")
        cleanup_end = self.script.index("trap cleanup EXIT", cleanup_start)
        cleanup = self.script[cleanup_start:cleanup_end]
        self.assertIn('rm -rf -- "$work_dir"', cleanup)
        self.assertIn('rm -f -- "$cert_path"', cleanup)
        self.assertIn('security delete-keychain "$keychain"', cleanup)

    def test_dmg_retry_stops_on_unsafe_detach_status(self) -> None:
        retry_start = self.script.index("create_dmg_with_retry() {")
        retry_end = self.script.index("\n}\n", retry_start)
        retry = self.script[retry_start:retry_end]

        self.assertIn("local unsafe_detach_status=70", retry)
        self.assertIn('helper_status=$?', retry)
        self.assertIn(
            '[[ "$helper_status" -eq "$unsafe_detach_status" ]]', retry
        )
        self.assertIn('return "$helper_status"', retry)
        self.assertLess(
            retry.index('[[ "$helper_status" -eq "$unsafe_detach_status" ]]'),
            retry.index('sleep "$attempt"'),
        )

    def test_signing_failure_blocks_both_macos_release_uploads(self) -> None:
        for workflow_name in (
            "build-macos-amd64-release.yml",
            "build-macos-arm64-release.yml",
        ):
            with self.subTest(workflow=workflow_name):
                workflow = (ROOT / ".github" / "workflows" / workflow_name).read_text(
                    encoding="utf-8"
                )
                sign_step = workflow.index("sign_macos_release_with_retry.sh")
                release_upload = workflow.index("upload_release_assets.py")
                self.assertLess(sign_step, release_upload)
                nearby = workflow[max(0, sign_step - 250): sign_step + 250]
                self.assertNotIn("continue-on-error", nearby)

    def test_documentation_matches_fail_closed_secret_and_upload_behavior(self) -> None:
        documentation = (ROOT / "docs" / "MACOS_SIGNING.md").read_text(
            encoding="utf-8"
        )
        self.assertIn("四个必需 secret 全部未配置", documentation)
        self.assertIn("部分配置会 fail closed", documentation)
        self.assertIn("上传 Release asset 之前终止", documentation)
        self.assertNotIn("这步失败不会阻断 artifact 上传", documentation)
        self.assertIn("逐层执行", documentation)
        self.assertIn("`--deep` 只用于签名后的递归验证", documentation)
        self.assertIn("随机 `0700` 临时目录", documentation)
        self.assertIn("`certificate.p12`", documentation)
        self.assertIn("`security import -f pkcs12`", documentation)

    def test_mock_security_import_contract_requires_a_hint_for_pkcs12(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            fake_security = root / "security.py"
            write_fake_security(fake_security)
            log_path = root / "security.log"
            environment = os.environ.copy()
            environment["SECURITY_LOG"] = str(log_path)

            no_extension = root / "certificate"
            with_extension = root / "certificate.p12"
            no_extension.write_bytes(b"certificate")
            with_extension.write_bytes(b"certificate")

            rejected = subprocess.run(
                [sys.executable, str(fake_security), "import", str(no_extension)],
                env=environment,
                check=False,
            )
            accepted_by_extension = subprocess.run(
                [sys.executable, str(fake_security), "import", str(with_extension)],
                env=environment,
                check=False,
            )
            accepted_by_format = subprocess.run(
                [
                    sys.executable,
                    str(fake_security),
                    "import",
                    str(no_extension),
                    "-f",
                    "pkcs12",
                ],
                env=environment,
                check=False,
            )

            self.assertNotEqual(rejected.returncode, 0)
            self.assertEqual(accepted_by_extension.returncode, 0)
            self.assertEqual(accepted_by_format.returncode, 0)

    @unittest.skipUnless(BASH, "bash is required for the executable cleanup regression")
    def test_early_failure_cleans_empty_arrays_and_private_certificate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            fixture = Path(temporary_directory)
            repository = fixture / "repository"
            scripts = repository / "scripts"
            packaging = repository / "packaging"
            release = repository / "release"
            fake_bin = fixture / "fake-bin"
            temp_root = fixture / "temporary"
            for directory in (scripts, packaging, release, fake_bin, temp_root):
                directory.mkdir(parents=True, exist_ok=True)

            signing_script = scripts / SIGN_SCRIPT.name
            shutil.copyfile(SIGN_SCRIPT, signing_script)
            signing_script.chmod(0o755)
            (packaging / "macos-entitlements.plist").write_text(
                "<plist version=\"1.0\"></plist>\n", encoding="utf-8"
            )
            for helper_name in (
                "create_macos_drag_dmg.sh",
                "validate_macos_dmg_layout.sh",
            ):
                write_executable(scripts / helper_name, "#!/bin/sh\nexit 0\n")
            (release / "LizzieYzy-mac-apple-silicon-test.dmg").write_bytes(b"dmg")

            security_log = fixture / "security.log"
            write_fake_security(fake_bin / "security")
            for command_name in ("codesign", "xcrun"):
                write_executable(fake_bin / command_name, "#!/bin/sh\nexit 0\n")
            write_executable(
                fake_bin / "openssl",
                "#!/bin/sh\nprintf '%s\\n' 0123456789abcdef0123456789abcdef\n",
            )

            environment = os.environ.copy()
            environment.update(
                {
                    "APPLE_CERT_P12": base64.b64encode(b"certificate").decode(),
                    "APPLE_CERT_PASSWORD": "",
                    "APPLE_ID": "qa@example.invalid",
                    "APPLE_APP_PASSWORD": "qa-app-password",
                    "APPLE_TEAM_ID": "QA12345678",
                    "APPLE_SIGN_IDENTITY": "",
                    "BASH_COMPAT": "3.2",
                    "PATH": str(fake_bin) + os.pathsep + environment.get("PATH", ""),
                    "SECURITY_LOG": str(security_log),
                    "FAKE_SECURITY_FAIL_IMPORT": "1",
                    "TMPDIR": str(temp_root),
                }
            )

            completed = subprocess.run(
                [
                    BASH,
                    str(signing_script),
                    str(release),
                    "mac-arm64",
                    "v-test",
                ],
                cwd=repository,
                env=environment,
                text=True,
                capture_output=True,
                timeout=30,
                check=False,
            )

            self.assertNotEqual(completed.returncode, 0, completed.stderr)
            self.assertNotIn("unbound variable", completed.stderr.lower())
            self.assertTrue(
                security_log.is_file(),
                "fake security command was not reached\n"
                f"return code: {completed.returncode}\n"
                f"stdout:\n{completed.stdout}\n"
                f"stderr:\n{completed.stderr}",
            )

            records = [
                json.loads(line)
                for line in security_log.read_text(encoding="utf-8").splitlines()
            ]
            imported = next(record for record in records if record["args"][0] == "import")
            certificate_path = Path(imported["certificate"])
            self.assertEqual(certificate_path.name, "certificate.p12")
            self.assertTrue(imported["explicit_pkcs12"])
            self.assertTrue(imported["forced_failure"])
            self.assertEqual(imported["certificate_mode"], 0o600)
            self.assertEqual(imported["parent_mode"], 0o700)
            self.assertFalse(certificate_path.exists())
            self.assertFalse(certificate_path.parent.exists())
            self.assertTrue(
                any(record["args"][0] == "delete-keychain" for record in records)
            )


if __name__ == "__main__":
    unittest.main()
