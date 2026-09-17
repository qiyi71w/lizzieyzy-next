#!/usr/bin/env python3
"""Copy verified source builds between releases without executing downloaded code."""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
from types import SimpleNamespace

try:
    from scripts.katago_asset_catalog import validate_catalog
    from scripts.katago_source_targets import SOURCE_COMMIT, TARGETS
    from scripts.upload_release_assets import UploadClient, completed, fingerprint, upload_one
    from scripts.validate_release_workflow_identity import require
except ModuleNotFoundError:
    from katago_asset_catalog import validate_catalog
    from katago_source_targets import SOURCE_COMMIT, TARGETS
    from upload_release_assets import UploadClient, completed, fingerprint, upload_one
    from validate_release_workflow_identity import require


def validate_transfer_catalog(catalog, repository, tag):
    validate_catalog(catalog)
    require(catalog.get('origin') == 'project-source-build', 'Not a project source build')
    require(catalog.get('katagoSourceCommit') == SOURCE_COMMIT, 'Unexpected source commit')
    require(catalog.get('engineReleaseRepository') == repository, 'Unexpected engine repository')
    require(catalog.get('engineReleaseTag') == tag, 'Unexpected engine release tag')
    records = catalog['assets']
    require(set(records) == set(TARGETS), 'Incomplete source target matrix')
    require(sum(record['sizeBytes'] for record in records.values()) <= 2_000_000_000,
            'Source inventory exceeds transfer budget')
    return records


def verify_source_inventory(rows, records):
    expected = {record['assetName']: record for record in records.values()}
    require(len(expected) == len(records), 'Duplicate catalog asset name')
    selected = [row for row in rows if row.get('name') in expected]
    require(len(selected) == len(expected) and len({row['name'] for row in selected}) == len(expected),
            'Missing or duplicate source archive')
    for row in selected:
        record = expected[row['name']]
        require(row.get('state') == 'uploaded' and row.get('size') == record['sizeBytes']
                and row.get('digest') == 'sha256:' + record['sha256'],
                'Source archive metadata differs from pinned catalog')


def transfer_archive(client, source_tag, record, download):
    name, size, digest = record['assetName'], record['sizeBytes'], 'sha256:' + record['sha256']
    if completed(client.lookup(name), size, digest):
        print(f'Verified existing asset: {name}', flush=True)
        return
    with tempfile.TemporaryDirectory(prefix='verified-katago-transfer-') as directory:
        path = Path(directory) / name
        download(source_tag, path)
        require(fingerprint(path) == (size, digest), 'Downloaded archive differs from pinned catalog')
        upload_one(client, path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-tag', required=True)
    parser.add_argument('--target-tag', required=True)
    parser.add_argument('--target-sha', required=True)
    args = parser.parse_args()
    tag_pattern = r'next-(\d{4}-\d{2}-\d{2})\.[1-9][0-9]*'
    target = re.fullmatch(tag_pattern, args.target_tag)
    require(target is not None and re.fullmatch(tag_pattern, args.source_tag) is not None,
            'Invalid release tag')
    require(args.source_tag != args.target_tag, 'Source and destination must differ')
    repository = os.environ.get('GITHUB_REPOSITORY', '')
    require(repository == 'wimi321/lizzieyzy-next', 'Unexpected repository')
    client = UploadClient(SimpleNamespace(
        repository=repository, release_tag=args.target_tag, date_tag=target.group(1),
        prerelease='true', target_sha=args.target_sha, github_ref='refs/tags/' + args.target_tag,
        timeout=900))
    client.guard()
    # Read only the immutable target's data, never run its scripts with write permissions.
    payload = client.get_json(
        f'/repos/{repository}/contents/src/main/resources/katago-assets.json?ref={args.target_sha}')
    require(payload.get('encoding') == 'base64' and 0 < payload.get('size', 0) <= 1_000_000,
            'Invalid catalog response')
    catalog = json.loads(base64.b64decode(''.join(payload['content'].split()), validate=True))
    records = validate_transfer_catalog(catalog, repository, args.target_tag)
    sources = [release for release in client.releases() if release.get('tag_name') == args.source_tag]
    require(len(sources) == 1, 'Source release must exist exactly once')
    source_id = sources[0].get('id')
    require(type(source_id) is int and source_id > 0, 'Invalid source release ID')
    rows = []
    for page in range(1, 101):
        batch = client.get_json(f'/repos/{repository}/releases/{source_id}/assets?per_page=100&page={page}')
        require(isinstance(batch, list), 'Invalid source asset inventory')
        rows.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise ValueError('Source inventory exceeds pagination limit')
    verify_source_inventory(rows, records)

    def download(source_tag, path):
        subprocess.run(['gh', 'release', 'download', source_tag, '--repo', repository,
                        '--pattern', path.name, '--dir', str(path.parent)],
                       check=True, capture_output=True, timeout=900)

    for record in sorted(records.values(), key=lambda item: (item['sizeBytes'], item['assetName'])):
        transfer_archive(client, args.source_tag, record, download)
    client.guard()
    print(f'All {len(records)} pinned source archives verified; destination remains Draft', flush=True)


if __name__ == '__main__':
    main()
