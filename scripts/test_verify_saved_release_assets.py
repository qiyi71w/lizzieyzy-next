from copy import deepcopy
from pathlib import Path
import tempfile
import unittest

from scripts.release_asset_provenance import build_provenance, expected_asset_names, ProvenanceError
from scripts.verify_saved_release_assets import verify_saved_assets


class SavedAssetsTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.directory = Path(temp.name)
        self.identity = dict(platform='windows', date_tag='2026-09-18',
                             release_tag='next-2026-09-18.2', target_sha='a'*40, run_id=123)
        for name in expected_asset_names('windows', '2026-09-18'):
            (self.directory / name).write_bytes(name.encode())
        self.original = build_provenance(self.directory, **self.identity, run_attempt=1)

    def verify(self, original=None, attempt=2):
        return verify_saved_assets(self.directory, original or self.original,
                                   **self.identity, run_attempt=attempt)

    def test_retry_reuses_exact_tested_bytes(self):
        result = self.verify()
        self.assertEqual(result['assets'], self.original['assets'])
        self.assertEqual(result['workflowRunAttempt'], 2)
        self.assertEqual(self.original['workflowRunAttempt'], 1)

    def test_first_attempt_is_valid(self):
        self.assertEqual(self.verify(attempt=1), self.original)

    def test_other_run_commit_release_or_platform_rejected(self):
        for key, value in (('workflowRunId', 999), ('targetSha', 'b'*40),
                           ('releaseTag', 'next-2026-09-18.3'), ('platform', 'linux')):
            with self.subTest(key=key):
                original = deepcopy(self.original)
                original[key] = value
                with self.assertRaises(ProvenanceError):
                    self.verify(original)

    def test_future_or_invalid_build_attempt_rejected(self):
        for value in (0, 3, True, '1', None):
            original = deepcopy(self.original)
            original['workflowRunAttempt'] = value
            with self.subTest(value=value), self.assertRaises(ProvenanceError):
                self.verify(original)

    def test_changed_or_missing_file_rejected(self):
        path = self.directory / self.original['assets'][0]['name']
        path.write_bytes(b'corrupted')
        with self.assertRaises(ProvenanceError):
            self.verify()
        path.unlink()
        with self.assertRaises(ProvenanceError):
            self.verify()

    def test_extra_file_rejected(self):
        (self.directory / 'unexpected.exe').write_bytes(b'extra')
        with self.assertRaises(ProvenanceError):
            self.verify()

    def test_workflow_retains_builds_and_retries_only_upload(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/build-windows-release.yml').read_text()
        build, upload = workflow.split('\n  upload:\n')
        self.assertIn('retention-days: 1', build)
        self.assertIn('compression-level: 0', build)
        self.assertIn('needs: build', upload)
        self.assertIn('runs-on: ubuntu-latest', upload)
        self.assertNotIn('package_windows_exe.sh', upload)
        self.assertNotIn('overwrite:', workflow)
        self.assertLess(upload.index('verify_saved_release_assets.py'),
                        upload.index('upload_release_assets.py'))


if __name__ == '__main__':
    unittest.main()
