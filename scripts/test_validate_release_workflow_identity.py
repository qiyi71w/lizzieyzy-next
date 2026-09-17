#!/usr/bin/env python3
"""Regression tests for release-mutating workflow identity guards."""

from __future__ import annotations

import inspect
import io
from pathlib import Path
import re
import unittest
from unittest import mock

from scripts import publish_release_request as publisher
from scripts import validate_release_workflow_identity as identity


DATE_TAG = "2026-08-19"
RELEASE_TAG = f"next-{DATE_TAG}.1"
TARGET_SHA = "a" * 40
REPOSITORY = "wimi321/lizzieyzy-next"


def release(**overrides: object) -> dict[str, object]:
    value: dict[str, object] = {
        "tag_name": RELEASE_TAG,
        "target_commitish": TARGET_SHA,
        "draft": True,
        "prerelease": True,
    }
    value.update(overrides)
    return value


class ReleaseWorkflowIdentityTest(unittest.TestCase):
    def validate(self, **overrides: object) -> dict[str, object]:
        arguments: dict[str, object] = {
            "date_tag": DATE_TAG,
            "release_tag": RELEASE_TAG,
            "prerelease": True,
            "repository": REPOSITORY,
            "tag_sha": TARGET_SHA,
            "releases": [release()],
            "require_draft": True,
            "target_sha": TARGET_SHA,
            "github_ref": f"refs/tags/{RELEASE_TAG}",
        }
        arguments.update(overrides)
        return identity.validate_requested_identity(**arguments)

    def test_accepts_exact_draft_tag_date_prerelease_and_target(self) -> None:
        self.assertEqual(RELEASE_TAG, self.validate()["tag_name"])

    def test_rejects_stale_or_invalid_date_tag_pair(self) -> None:
        for date_tag, release_tag in (
            ("2026-08-18", RELEASE_TAG),
            ("2026-02-30", "next-2026-02-30.1"),
            (DATE_TAG, f"next-{DATE_TAG}.0"),
        ):
            with self.subTest(date_tag=date_tag, release_tag=release_tag):
                with self.assertRaises(identity.IdentityError):
                    self.validate(date_tag=date_tag, release_tag=release_tag)

    def test_rejects_ambiguous_or_missing_release(self) -> None:
        for releases in ([], [release(), release()]):
            with self.subTest(count=len(releases)):
                with self.assertRaisesRegex(identity.IdentityError, "exactly one"):
                    self.validate(releases=releases)

    def test_rejects_release_target_or_tag_commit_mismatch(self) -> None:
        with self.assertRaisesRegex(identity.IdentityError, "target_commitish"):
            self.validate(releases=[release(target_commitish="b" * 40)])
        with self.assertRaisesRegex(identity.IdentityError, "target SHA"):
            self.validate(target_sha="b" * 40)

    def test_rejects_prerelease_mismatch(self) -> None:
        with self.assertRaisesRegex(identity.IdentityError, "prerelease"):
            self.validate(releases=[release(prerelease=False)])

    def test_rejects_non_draft_upload_target(self) -> None:
        with self.assertRaisesRegex(identity.IdentityError, "existing draft"):
            self.validate(releases=[release(draft=False)])

    def test_rejects_dispatch_from_non_tag_ref(self) -> None:
        with self.assertRaisesRegex(identity.IdentityError, "exact release tag ref"):
            self.validate(github_ref="refs/heads/main")

    def test_notes_update_can_validate_published_release_without_build_ref(self) -> None:
        validated = self.validate(
            releases=[release(draft=False)],
            require_draft=False,
            target_sha=None,
            github_ref=None,
        )
        self.assertFalse(validated["draft"])


class GitHubIdentityRetryTest(unittest.TestCase):
    class Response:
        def __enter__(self) -> "GitHubIdentityRetryTest.Response":
            return self

        def __exit__(self, *_args: object) -> None:
            return None

        def read(self, _limit: int | None = None) -> bytes:
            return b'{"ok": true}'

    def test_retries_429_5xx_and_rate_limited_403(self) -> None:
        for status, headers in (
            (429, {"Retry-After": "0"}),
            (502, {}),
            (403, {"X-RateLimit-Remaining": "0", "Retry-After": "0"}),
        ):
            with self.subTest(status=status):
                delays: list[float] = []
                client = identity.GitHubIdentityClient(
                    REPOSITORY,
                    "token",
                    "https://api.github.test",
                    sleep=delays.append,
                    retry_attempts=2,
                )
                failure = identity.HTTPError(
                    "https://api.github.test/example",
                    status,
                    "transient",
                    headers,
                    io.BytesIO(b"try again"),
                )
                with mock.patch.object(
                    identity,
                    "urlopen",
                    side_effect=[failure, self.Response()],
                ) as request:
                    self.assertEqual({"ok": True}, client.get_json("/example"))
                self.assertEqual(2, request.call_count)
                self.assertEqual(1, len(delays))


class ReleaseWorkflowIdentityWiringTest(unittest.TestCase):
    root = Path(__file__).resolve().parents[1]
    build_workflows = (
        "build-windows-release.yml",
        "build-linux-release.yml",
        "build-macos-amd64-release.yml",
        "build-macos-arm64-release.yml",
    )

    def workflow(self, name: str) -> str:
        return (self.root / ".github/workflows" / name).read_text(encoding="utf-8")

    @staticmethod
    def shell_bodies(workflow: str) -> tuple[str, ...]:
        """Extract run values well enough to audit expressions without a YAML dependency."""
        lines = workflow.splitlines()
        bodies: list[str] = []
        for index, line in enumerate(lines):
            match = re.match(r"^(\s*)run:\s*(.*)$", line)
            if match is None:
                continue
            indent = len(match.group(1))
            value = match.group(2)
            if value not in {"|", "|-", "|+", ">", ">-", ">+"}:
                bodies.append(value)
                continue
            block: list[str] = []
            for nested in lines[index + 1 :]:
                if nested.strip() and len(nested) - len(nested.lstrip()) <= indent:
                    break
                block.append(nested)
            bodies.append("\n".join(block))
        return tuple(bodies)

    def test_release_mutating_workflows_have_no_stale_date_or_tag_defaults(self) -> None:
        for name in (*self.build_workflows, "update-release-notes.yml"):
            with self.subTest(workflow=name):
                workflow = self.workflow(name)
                self.assertNotIn("default: 2026-", workflow)
                self.assertNotRegex(workflow, r"default:\s+next-")
                self.assertIn("release_prerelease:", workflow)
                self.assertIn("validate_release_workflow_identity.py", workflow)

    def test_build_run_names_attest_exact_dispatch_inputs(self) -> None:
        expected_names = {
            spec.workflow_file: spec.expected_run_name(
                "${{ inputs.date_tag }}",
                "${{ inputs.release_tag }}",
                "${{ inputs.release_prerelease }}",
            )
            for spec in publisher.WORKFLOWS
        }
        for name in self.build_workflows:
            with self.subTest(workflow=name):
                workflow = self.workflow(name)
                self.assertIn(f'run-name: "{expected_names[name]}"', workflow)
                self.assertIn("--target-sha \"$RELEASE_TARGET_SHA\"", workflow)
                self.assertIn("--github-ref \"$RELEASE_GITHUB_REF\"", workflow)
                self.assertIn("--require-draft", workflow)

    def test_dispatch_inputs_are_env_isolated_from_every_shell(self) -> None:
        # A payload such as `next-$(touch owned).1` must only become inert env data;
        # GitHub must never splice it into generated Bash or PowerShell source.
        for name in (*self.build_workflows, "update-release-notes.yml"):
            with self.subTest(workflow=name):
                workflow = self.workflow(name)
                self.assertIn("RELEASE_DATE_TAG: ${{ inputs.date_tag }}", workflow)
                self.assertIn("RELEASE_TAG: ${{ inputs.release_tag }}", workflow)
                self.assertIn(
                    "RELEASE_PRERELEASE: ${{ inputs.release_prerelease }}", workflow
                )
                for line in workflow.splitlines():
                    if "${{ inputs." not in line:
                        continue
                    self.assertTrue(
                        line.startswith("run-name:")
                        or re.match(r"^\s+RELEASE_[A-Z_]+:", line) is not None
                        or line.strip()
                        == "group: release-${{ github.workflow }}-${{ inputs.release_tag }}",
                        f"dispatch input escapes env isolation in {name}: {line}",
                    )
                for body in self.shell_bodies(workflow):
                    self.assertNotIn("${{ inputs.", body)
                    self.assertNotIn("eval ", body)
                    self.assertNotIn("bash -c", body)
                    self.assertNotIn("sh -c", body)

    def test_token_bearing_shells_use_quoted_env_identity(self) -> None:
        for name in (*self.build_workflows, "update-release-notes.yml"):
            with self.subTest(workflow=name):
                workflow = self.workflow(name)
                self.assertIn('--date-tag "$RELEASE_DATE_TAG"', workflow)
                self.assertIn('--release-tag "$RELEASE_TAG"', workflow)
                self.assertIn('--prerelease "$RELEASE_PRERELEASE"', workflow)
                self.assertIn('--repository "$RELEASE_REPOSITORY"', workflow)

    def test_builds_publish_run_bound_provenance_after_platform_gates(self) -> None:
        platforms = {
            "build-windows-release.yml": "windows",
            "build-linux-release.yml": "linux",
            "build-macos-amd64-release.yml": "mac-amd64",
            "build-macos-arm64-release.yml": "mac-arm64",
        }
        validation_steps = {
            "build-windows-release.yml": "Validate public Windows assets",
            "build-linux-release.yml": "Validate public Linux assets",
            "build-macos-amd64-release.yml": "Validate public macOS amd64 assets",
            "build-macos-arm64-release.yml": "Validate public macOS arm64 assets",
        }
        for name, platform in platforms.items():
            with self.subTest(workflow=name):
                workflow = self.workflow(name)
                create = workflow.index("Create run-bound release asset provenance")
                provenance_upload = workflow.index(
                    "Upload run-bound release asset provenance"
                )
                release_upload = workflow.index(
                    "Upload verified assets to draft release"
                )
                self.assertLess(workflow.index(validation_steps[name]), create)
                self.assertLess(create, provenance_upload)
                self.assertLess(provenance_upload, release_upload)
                self.assertIn(f"--platform {platform}", workflow)
                self.assertIn('--target-sha "$RELEASE_TARGET_SHA"', workflow)
                self.assertIn('--run-id "$RELEASE_RUN_ID"', workflow)
                self.assertIn('--run-attempt "$RELEASE_RUN_ATTEMPT"', workflow)
                self.assertIn(
                    f"name: release-asset-provenance-{platform}-attempt-"
                    "${{ github.run_attempt }}",
                    workflow,
                )
                self.assertIn("if-no-files-found: error", workflow[create:release_upload])

    def test_every_upload_is_guarded_immutable_and_windows_upload_is_last(self) -> None:
        for name in self.build_workflows:
            with self.subTest(workflow=name):
                workflow = self.workflow(name)
                self.assertNotIn("--clobber", workflow)
                self.assertEqual(1, workflow.count("upload_release_assets.py"))
                self.assertEqual(2, workflow.count("validate_release_workflow_identity.py"))
                upload = workflow.index("Upload verified assets to draft release")
                final_guard = workflow.index(
                    "validate_release_workflow_identity.py", upload
                )
                self.assertLess(
                    final_guard,
                    workflow.index("upload_release_assets.py"),
                )
                self.assertIn("timeout-minutes: 90", workflow[upload:])

        windows = self.workflow("build-windows-release.yml")
        upload = windows.index("Upload verified assets to draft release")
        self.assertLess(windows.index("Smoke test bundled Windows app images"), upload)
        self.assertLess(windows.index("Smoke test Windows upgrade install path"), upload)
        self.assertLess(windows.index("Upload Windows smoke logs"), upload)
        provenance = windows.index("Create run-bound release asset provenance")
        self.assertLess(windows.index("Smoke test bundled Windows app images"), provenance)
        self.assertLess(windows.index("Smoke test Windows upgrade install path"), provenance)
        self.assertLess(windows.index("Upload Windows smoke logs"), provenance)

    def test_windows_build_timeout_fits_publisher_wait_budget(self) -> None:
        workflow = self.workflow("build-windows-release.yml")
        self.assertRegex(
            workflow,
            r"runs-on: windows-latest\s+timeout-minutes: 280",
        )
        self.assertNotIn("timeout-minutes: 300", workflow)
        publisher_wait = inspect.signature(publisher.ReleasePublisher).parameters[
            "run_timeout_seconds"
        ].default
        self.assertGreater(publisher_wait, 280 * 60)

    def test_windows_katago_version_audit_uses_runtime_checks_when_possible(self) -> None:
        workflow = self.workflow("build-windows-release.yml")
        runtime_start = workflow.index("runtime_version_engines=(")
        static_start = workflow.index("driver_gated_static_engines=(")
        runtime_block = workflow[runtime_start:static_start]
        static_end = workflow.index("tensorrt_engine=", static_start)
        static_block = workflow[static_start:static_end]

        for flavor in (
            "app-image-with-katago",
            "app-image-opencl",
            "app-image-experimental.directml",
            "app-image-experimental.openvino",
            "app-image-experimental.rocm.gfx103x",
            "app-image-experimental.rocm.gfx110x",
            "app-image-experimental.rocm.gfx1151",
            "app-image-experimental.rocm.gfx120x",
        ):
            self.assertIn(flavor, runtime_block)
        self.assertNotIn("app-image-nvidia/", runtime_block)
        self.assertNotIn("app-image-nvidia.tensorrt", runtime_block)

        self.assertIn("app-image-nvidia/", static_block)
        self.assertIn("katago-human-sl-cuda.exe", static_block)
        self.assertNotIn("app-image-experimental", static_block)
        self.assertIn('for engine in "${runtime_version_engines[@]}"', workflow)
        self.assertIn('version_output="$("$engine" version 2>&1)"', workflow)
        self.assertIn(
            '"${driver_gated_static_engines[@]}"',
            workflow,
        )

    def test_publisher_explicit_waits_leave_job_timeout_margin(self) -> None:
        workflow = (
            self.root / ".github/workflows/publish-requested-pre-release.yml"
        ).read_text(encoding="utf-8")
        job_timeout_match = re.search(r"timeout-minutes:\s*(\d+)", workflow)
        self.assertIsNotNone(job_timeout_match)
        assert job_timeout_match is not None
        job_timeout_seconds = int(job_timeout_match.group(1)) * 60
        parameters = inspect.signature(publisher.ReleasePublisher).parameters
        run_wait = parameters["run_timeout_seconds"].default
        ci_wait = parameters["ci_timeout_seconds"].default
        discovery_wait = inspect.signature(
            publisher.ReleasePublisher._discover_new_run
        ).parameters["timeout_seconds"].default
        explicit_worst_case = run_wait + (2 * ci_wait) + (
            len(publisher.WORKFLOWS) * discovery_wait
        )
        self.assertLessEqual(explicit_worst_case, job_timeout_seconds - 25 * 60)

    def test_notes_edit_is_identity_guarded(self) -> None:
        workflow = self.workflow("update-release-notes.yml")
        update_step = workflow.index("Update GitHub release body")
        identity_guard = workflow.index(
            "validate_release_workflow_identity.py", update_step
        )
        notes_guard = workflow.index("validate_release_notes.py", update_step)
        edit = workflow.index("gh release edit", update_step)
        self.assertLess(identity_guard, notes_guard)
        self.assertLess(notes_guard, edit)
        self.assertIn('--notes-file "$RELEASE_NOTES_PATH"', workflow[notes_guard:])

    def test_notes_update_shares_publish_lock_and_protects_audited_tag(self) -> None:
        workflow = self.workflow("update-release-notes.yml")
        self.assertIn(
            "concurrency:\n  group: publish-requested-pre-release\n"
            "  cancel-in-progress: false",
            workflow,
        )
        audited_guard = (
            'if [[ "$RELEASE_TAG" == "next-2026-08-19.1" || '
            '"$RELEASE_TAG" == "next-2026-08-19.2" || '
            '"$RELEASE_TAG" == "next-2026-08-19.3" || '
            '"$RELEASE_TAG" == "next-2026-08-19.4" ]]; then'
        )
        self.assertEqual(workflow.count(audited_guard), 2)
        generate_step = workflow.index("Generate release notes")
        first_guard = workflow.index(audited_guard)
        first_guard_end = workflow.index("          fi", first_guard)
        self.assertLess(first_guard, generate_step)
        self.assertIn("exit 1", workflow[first_guard:first_guard_end])

        update_step = workflow.index("Update GitHub release body")
        second_guard = workflow.index(audited_guard, update_step)
        second_guard_end = workflow.index("          fi", second_guard)
        self.assertLess(second_guard, workflow.index("gh release edit", update_step))
        self.assertIn("exit 1", workflow[second_guard:second_guard_end])

    def test_release_checklist_requires_explicit_notes_prerelease_identity(self) -> None:
        checklist = (self.root / "docs/RELEASE_CHECKLIST.md").read_text(
            encoding="utf-8"
        )
        command = checklist[checklist.index("gh workflow run update-release-notes.yml") :]
        command = command[: command.index("```")]
        self.assertIn("-f release_prerelease=true", command)


if __name__ == "__main__":
    unittest.main()
