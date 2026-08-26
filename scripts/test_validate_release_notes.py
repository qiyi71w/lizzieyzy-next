#!/usr/bin/env python3
"""Tests for the fail-closed generated release-notes gate."""

from __future__ import annotations

import unittest

from scripts import publish_release_request as publisher
from scripts import validate_release_notes as validator


DATE_TAG = "2026-08-20"
RELEASE_TAG = f"next-{DATE_TAG}.1"
REPOSITORY = "wimi321/lizzieyzy-next"


def complete_notes() -> str:
    blocks: list[str] = []
    for heading in publisher.LOCALIZED_NOTE_HEADINGS:
        links = []
        for name in publisher.direct_download_names(DATE_TAG):
            url = (
                f"https://github.com/{REPOSITORY}/releases/download/"
                f"{RELEASE_TAG}/{name}"
            )
            links.append(f"- [`{name}`]({url})")
        blocks.append(
            "\n".join(
                (
                    heading,
                    RELEASE_TAG,
                    "### Updates",
                    "Reviewed changes.",
                    "### Before upgrading",
                    "Back up your settings.",
                    "### Downloads",
                    *links,
                    "### Contact",
                    "Community support.",
                )
            )
        )
    return "\n\n---\n\n".join(blocks)


class GeneratedReleaseNotesValidationTest(unittest.TestCase):
    def validate(self, body: str | None = None, **overrides: str) -> None:
        arguments = {
            "date_tag": DATE_TAG,
            "release_tag": RELEASE_TAG,
            "repository": REPOSITORY,
        }
        arguments.update(overrides)
        validator.validate_generated_release_notes(
            complete_notes() if body is None else body,
            **arguments,
        )

    def test_accepts_complete_notes_for_a_new_release_identity(self) -> None:
        self.validate()

    def test_refuses_to_overwrite_the_manually_audited_current_release(self) -> None:
        for protected in (
            "next-2026-08-19.1",
            "next-2026-08-19.2",
            "next-2026-08-19.3",
            "next-2026-08-19.4",
        ):
            with self.subTest(protected=protected):
                body = (
                    complete_notes()
                    .replace(RELEASE_TAG, protected)
                    .replace(DATE_TAG, "2026-08-19")
                )
                with self.assertRaisesRegex(
                    validator.NotesValidationError, "manually audited"
                ):
                    self.validate(body, date_tag="2026-08-19", release_tag=protected)

    def test_rejects_unresolved_markers(self) -> None:
        with self.assertRaisesRegex(validator.NotesValidationError, "FULL_TEST_COUNT"):
            self.validate(complete_notes() + "\nFULL_TEST_COUNT\n")

    def test_rejects_stale_date_or_invalid_repository(self) -> None:
        with self.assertRaisesRegex(validator.NotesValidationError, "match date_tag"):
            self.validate(release_tag="next-2026-08-21.1")
        with self.assertRaisesRegex(validator.NotesValidationError, "owner/name"):
            self.validate(repository="https://github.com/wimi321/lizzieyzy-next")

    def test_rejects_missing_direct_download_link(self) -> None:
        name = publisher.direct_download_names(DATE_TAG)[0]
        url = (
            f"https://github.com/{REPOSITORY}/releases/download/"
            f"{RELEASE_TAG}/{name}"
        )
        with self.assertRaisesRegex(validator.NotesValidationError, "directly link"):
            self.validate(complete_notes().replace(f"- [`{name}`]({url})\n", "", 1))


if __name__ == "__main__":
    unittest.main()
