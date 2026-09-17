import argparse
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

from scripts.upload_release_assets import (
    IdentityError, UploadClient, completed, fingerprint, upload_one,
)


class FakeClient:
    def __init__(self, path, behaviors, asset=None):
        self.path = path
        self.behaviors = iter(behaviors)
        self.asset = asset
        self.uploads = 0
        self.deletes = 0

    def lookup(self, name):
        return self.asset

    def delete_starter(self, asset, size):
        assert asset['state'] == 'starter'
        self.deletes += 1
        self.asset = None

    def upload(self, path):
        self.uploads += 1
        size, digest = fingerprint(path)
        behavior = next(self.behaviors)
        self.asset = {'id': 7, 'name': path.name, 'size': size,
                      'state': 'starter', 'digest': None}
        if behavior in ('success', 'lost-response', 'mutated'):
            self.asset.update(state='uploaded', digest=digest)
        if behavior == 'mutated':
            path.write_bytes(b'changed')
        if behavior == 'timeout':
            raise subprocess.TimeoutExpired(['gh'], 900)
        if behavior in ('500', 'lost-response'):
            raise subprocess.CalledProcessError(1, ['gh'], stderr=b'HTTP 500: server error')


class UploadTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'package.zip'
        self.path.write_bytes(b'verified package')
        self.size, self.digest = fingerprint(self.path)

    def record(self, **overrides):
        return dict({'id': 7, 'name': self.path.name, 'state': 'uploaded',
                     'size': self.size, 'digest': self.digest}, **overrides)

    def run_upload(self, client, **kwargs):
        upload_one(client, self.path, sleep=lambda _: None, **kwargs)

    def test_http_500_cleans_only_partial_then_retries(self):
        client = FakeClient(self.path, ['500', 'success'])
        self.run_upload(client)
        self.assertEqual((client.uploads, client.deletes), (2, 1))

    def test_timeout_reconciles_before_retry(self):
        client = FakeClient(self.path, ['timeout', 'success'])
        self.run_upload(client)
        self.assertEqual(client.deletes, 1)

    def test_lost_success_response_does_not_upload_twice(self):
        client = FakeClient(self.path, ['lost-response'])
        self.run_upload(client)
        self.assertEqual((client.uploads, client.deletes), (1, 0))

    def test_matching_complete_asset_is_immutable(self):
        client = FakeClient(self.path, [], self.record())
        self.run_upload(client)
        self.assertEqual((client.uploads, client.deletes), (0, 0))

    def test_different_complete_asset_never_deleted(self):
        for overrides in ({'digest': 'sha256:' + '0'*64}, {'size': self.size + 1}, {'digest': None}):
            with self.subTest(overrides=overrides):
                client = FakeClient(self.path, [], self.record(**overrides))
                with self.assertRaises(IdentityError):
                    self.run_upload(client)
                self.assertEqual((client.uploads, client.deletes), (0, 0))

    def test_unknown_partial_state_is_not_deleted(self):
        for overrides in ({'state': 'unknown'}, {'state': 'starter'},
                          {'state': 'starter', 'digest': None, 'size': self.size+1}):
            with self.subTest(overrides=overrides):
                client = FakeClient(self.path, [], self.record(**overrides))
                with self.assertRaises(IdentityError):
                    self.run_upload(client)
                self.assertEqual(client.deletes, 0)

    def test_exhausted_retries_fail_closed(self):
        client = FakeClient(self.path, ['500'] * 4)
        with self.assertRaisesRegex(IdentityError, r'did not complete.*HTTP 500'):
            self.run_upload(client)
        self.assertEqual((client.uploads, client.deletes), (4, 3))

    def test_successful_process_without_uploaded_asset_is_not_success(self):
        client = FakeClient(self.path, ['incomplete'] * 2)
        with self.assertRaises(IdentityError):
            self.run_upload(client, attempts=2)

    def test_modified_local_file_fails(self):
        client = FakeClient(self.path, ['mutated'])
        with self.assertRaisesRegex(IdentityError, 'changed during'):
            self.run_upload(client)

    def test_empty_asset_rejected(self):
        self.path.write_bytes(b'')
        with self.assertRaises(IdentityError):
            fingerprint(self.path)

    def test_symlink_asset_rejected(self):
        link = self.path.with_name('link.zip')
        try:
            link.symlink_to(self.path)
        except OSError as exc:
            self.skipTest(f'Symlink creation unavailable: {exc}')
        with self.assertRaises(IdentityError):
            fingerprint(link)

    @patch('scripts.upload_release_assets.subprocess.run')
    def test_upload_has_timeout_no_clobber_and_checks_identity(self, run):
        client = object.__new__(UploadClient)
        client.repository = 'owner/repo'
        client.args = argparse.Namespace(release_tag='next-2026-09-18.1', timeout=900)
        client.guard = Mock()
        client.upload(self.path)
        client.guard.assert_called_once()
        self.assertNotIn('--clobber', run.call_args.args[0])
        self.assertEqual(run.call_args.kwargs['timeout'], 900)
        self.assertTrue(run.call_args.kwargs['check'])

    @patch('scripts.upload_release_assets.subprocess.run')
    def test_completed_during_cleanup_is_not_deleted(self, run):
        client = object.__new__(UploadClient)
        client.lookup = Mock(return_value=self.record())
        with self.assertRaises(IdentityError):
            client.delete_starter(self.record(state='starter', digest=None), self.size)
        run.assert_not_called()

    def test_restart_recovers_prior_incomplete_asset(self):
        client = FakeClient(self.path, ['success'], self.record(state='starter', digest=None))
        self.run_upload(client)
        self.assertEqual((client.uploads, client.deletes), (1, 1))

    @patch('scripts.upload_release_assets.GitHubIdentityClient.get_json', return_value={})
    def test_identity_and_asset_reads_bypass_stale_cache(self, get_json):
        client = object.__new__(UploadClient)
        client.get_json('/release')
        client.get_json('/assets?per_page=100')
        urls = [call.args[0] for call in get_json.call_args_list]
        self.assertRegex(urls[0], r'^/release\?verification=\d+$')
        self.assertRegex(urls[1], r'^/assets\?per_page=100&verification=\d+$')

    @patch('scripts.upload_release_assets.subprocess.run')
    def test_published_release_blocks_upload(self, run):
        client = object.__new__(UploadClient)
        client.guard = Mock(side_effect=IdentityError('Not Draft'))
        with self.assertRaises(IdentityError):
            client.upload(self.path)
        run.assert_not_called()


if __name__ == '__main__':
    unittest.main()
