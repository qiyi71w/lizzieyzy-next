#!/usr/bin/env python3
"""Upload a bounded inventory without replacing completed release assets."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import time

try:
    from scripts.release_asset_topology import platforms, public_inventory
    from scripts.validate_release_workflow_identity import (
        GitHubIdentityClient, IdentityError, require, validate_requested_identity,
    )
except ModuleNotFoundError:
    from release_asset_topology import platforms, public_inventory
    from validate_release_workflow_identity import (
        GitHubIdentityClient, IdentityError, require, validate_requested_identity,
    )


class UploadClient(GitHubIdentityClient):
    def __init__(self, args):
        super().__init__(args.repository, os.environ.get('GITHUB_TOKEN', ''),
                         'https://api.github.com')
        self.args = args
        self.release_id = None

    def get_json(self, path):
        # Do not authorize mutations from an intermediary's cached Draft/asset state.
        separator = '&' if '?' in path else '?'
        return super().get_json(f'{path}{separator}verification={time.time_ns()}')

    def guard(self):
        args = self.args
        if self.release_id is None:
            releases = self.releases()
        else:
            releases = [self.get_json(f'/repos/{self.repository}/releases/{self.release_id}')]
        release = validate_requested_identity(
            args.date_tag, args.release_tag, args.prerelease == 'true',
            self.repository, self.tag_sha(args.release_tag), releases,
            require_draft=True, target_sha=args.target_sha, github_ref=args.github_ref,
        )
        release_id = release.get('id')
        require(type(release_id) is int and release_id > 0, 'Invalid release ID')
        require(self.release_id in (None, release_id), 'Release ID changed')
        self.release_id = release_id

    def lookup(self, name):
        self.guard()
        matches = []
        for page in range(1, 101):
            payload = self.get_json(
                f'/repos/{self.repository}/releases/{self.release_id}/assets?per_page=100&page={page}')
            require(isinstance(payload, list), 'Invalid asset inventory')
            matches.extend(item for item in payload if isinstance(item, dict) and item.get('name') == name)
            if len(payload) < 100:
                require(len(matches) <= 1, f'Duplicate release asset: {name}')
                return matches[0] if matches else None
        raise IdentityError('Asset inventory exceeds pagination limit')

    def delete_starter(self, asset, size):
        current = self.lookup(asset['name'])
        require(current == asset, 'Incomplete asset changed before cleanup')
        require(current['state'] == 'starter' and not current.get('digest')
                and current.get('size') in (0, size), 'Refusing to delete completed or unknown asset')
        asset_id = current.get('id')
        require(type(asset_id) is int and asset_id > 0, 'Invalid asset ID')
        self.guard()
        subprocess.run(['gh', 'api', '--method', 'DELETE',
                        f'repos/{self.repository}/releases/assets/{asset_id}'],
                       check=True, timeout=90, capture_output=True)

    def upload(self, path):
        self.guard()
        # One file per process avoids one HTTP 500 cancelling unrelated uploads.
        subprocess.run(['gh', 'release', 'upload', self.args.release_tag, str(path),
                        '--repo', self.repository], check=True, timeout=self.args.timeout,
                       capture_output=True)


def fingerprint(path):
    require(path.is_file() and not path.is_symlink(), f'Missing regular asset: {path.name}')
    with path.open('rb') as handle:
        digest = hashlib.file_digest(handle, 'sha256').hexdigest()
    size = path.stat().st_size
    require(size > 0, f'Empty asset: {path.name}')
    return size, 'sha256:' + digest


def completed(asset, size, digest):
    if asset is None:
        return False
    if asset.get('state') == 'uploaded':
        require(asset.get('size') == size and asset.get('digest') == digest,
                f'Completed asset differs; refusing overwrite: {asset.get("name")}')
        return True
    require(asset.get('state') == 'starter' and not asset.get('digest')
            and asset.get('size') in (0, size), 'Unexpected incomplete asset state')
    return False


def upload_one(client, path, *, attempts=4, sleep=time.sleep):
    require(attempts > 0, 'Upload attempts must be positive')
    size, digest = fingerprint(path)
    for attempt in range(1, attempts + 1):
        asset = client.lookup(path.name)
        if completed(asset, size, digest):
            print(f'Verified existing asset: {path.name}', flush=True)
            return
        if asset is not None:
            client.delete_starter(asset, size)
        require(fingerprint(path) == (size, digest), 'Local asset changed before upload')
        print(f'Uploading {path.name} ({size} bytes), attempt {attempt}/{attempts}', flush=True)
        error = None
        try:
            client.upload(path)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
            error = type(exc).__name__
            stderr = exc.stderr or b''
            if isinstance(stderr, bytes):
                stderr = stderr.decode('utf-8', errors='replace')
            status = re.search(r'\bHTTP [1-5][0-9]{2}\b', stderr)
            if status:
                error += ': ' + status.group(0)
        # A lost HTTP response can still mean success. Reconcile before any retry/delete.
        asset = client.lookup(path.name)
        if completed(asset, size, digest):
            require(fingerprint(path) == (size, digest), 'Local asset changed during upload')
            print(f'Uploaded and SHA-256 verified: {path.name}', flush=True)
            return
        if attempt == attempts:
            raise IdentityError(f'Upload did not complete: {path.name} ({error or "incomplete"})')
        print(f'Retrying only {path.name} after {error or "incomplete upload"}', flush=True)
        sleep(min(5 * attempt, 30))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', required=True, choices=platforms())
    parser.add_argument('--directory', default='dist/release')
    parser.add_argument('--date-tag', required=True)
    parser.add_argument('--release-tag', required=True)
    parser.add_argument('--prerelease', required=True, choices=('true', 'false'))
    parser.add_argument('--repository', required=True)
    parser.add_argument('--target-sha', required=True)
    parser.add_argument('--github-ref', required=True)
    parser.add_argument('--timeout', type=int, default=900)
    parser.add_argument('--attempts', type=int, default=4)
    args = parser.parse_args()
    try:
        require(1 <= args.attempts <= 5 and 30 <= args.timeout <= 1800, 'Invalid retry bounds')
        client = UploadClient(args)
        client.guard()
        paths = [Path(args.directory) / name for name in public_inventory(args.platform, args.date_tag)]
        for path in paths:
            fingerprint(path)
        for path in sorted(paths, key=lambda item: (item.stat().st_size, item.name)):
            upload_one(client, path, attempts=args.attempts)
        print(f'All {len(paths)} {args.platform} assets verified', flush=True)
        return 0
    except (IdentityError, OSError, subprocess.SubprocessError) as exc:
        print(f'Release upload failed: {exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
