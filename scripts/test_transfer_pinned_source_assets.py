from copy import deepcopy
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import Mock

from scripts.test_upload_release_assets import FakeClient
from scripts.transfer_pinned_source_assets import (
    transfer_archive, validate_transfer_catalog, verify_source_inventory,
)
from scripts.validate_release_workflow_identity import IdentityError


class TransferTests(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads((Path(__file__).resolve().parents[1]
                                   / 'src/main/resources/katago-assets.json').read_text())
        self.data = b'verified archive'
        self.record = {'assetName': 'engine.zip', 'sizeBytes': len(self.data),
                       'sha256': hashlib.sha256(self.data).hexdigest()}

    def validate(self, catalog):
        return validate_transfer_catalog(catalog, 'wimi321/lizzieyzy-next',
                                         self.catalog['engineReleaseTag'])

    def asset(self, **overrides):
        return dict({'name': self.record['assetName'], 'size': self.record['sizeBytes'],
                     'digest': 'sha256:' + self.record['sha256'], 'state': 'uploaded'}, **overrides)

    def test_full_pinned_matrix_accepted(self):
        self.assertEqual(len(self.validate(self.catalog)), 15)

    def test_wrong_identity_or_missing_target_rejected(self):
        for field, value in (('katagoSourceCommit', '0' * 40),
                             ('engineReleaseRepository', 'other/repo'),
                             ('engineReleaseTag', 'next-2026-01-01.1')):
            with self.subTest(field=field):
                catalog = deepcopy(self.catalog)
                catalog[field] = value
                with self.assertRaises((IdentityError, ValueError)):
                    self.validate(catalog)
        catalog = deepcopy(self.catalog)
        del catalog['assets']['linux-cpu']
        with self.assertRaises((IdentityError, ValueError)):
            self.validate(catalog)

    def test_budget_enforced(self):
        self.catalog['assets']['linux-cpu']['sizeBytes'] = 2_000_000_001
        with self.assertRaises((IdentityError, ValueError)):
            self.validate(self.catalog)

    def test_source_inventory_exact_and_complete(self):
        records = {'cpu': self.record}
        verify_source_inventory([self.asset()], records)
        for rows in ([], [self.asset(), self.asset()], [self.asset(state='starter')],
                     [self.asset(size=1)], [self.asset(digest='sha256:' + '0' * 64)]):
            with self.subTest(rows=rows), self.assertRaises(IdentityError):
                verify_source_inventory(rows, records)

    def test_matching_destination_skips_network(self):
        client = FakeClient(None, [], self.asset())
        download = Mock()
        transfer_archive(client, 'source', self.record, download)
        download.assert_not_called()
        self.assertEqual((client.uploads, client.deletes), (0, 0))

    def test_mismatched_destination_never_overwritten(self):
        client = FakeClient(None, [], self.asset(size=1))
        download = Mock()
        with self.assertRaises(IdentityError):
            transfer_archive(client, 'source', self.record, download)
        download.assert_not_called()
        self.assertEqual((client.uploads, client.deletes), (0, 0))

    def test_verified_download_uploaded_then_local_copy_removed(self):
        client = FakeClient(None, ['success'])
        paths = []

        def download(tag, path):
            self.assertEqual(tag, 'source')
            paths.append(path)
            path.write_bytes(self.data)

        transfer_archive(client, 'source', self.record, download)
        self.assertEqual((client.uploads, client.deletes), (1, 0))
        self.assertFalse(paths[0].exists())

    def test_corrupt_download_never_mutates_destination(self):
        client = FakeClient(None, [])
        with self.assertRaises(IdentityError):
            transfer_archive(client, 'source', self.record, lambda tag, path: path.write_bytes(b'bad'))
        self.assertEqual((client.uploads, client.deletes), (0, 0))


if __name__ == '__main__':
    unittest.main()
