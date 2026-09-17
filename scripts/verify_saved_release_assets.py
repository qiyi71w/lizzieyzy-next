#!/usr/bin/env python3
"""Revalidate immutable build outputs before retrying their release upload."""

import argparse
import json
import os
from pathlib import Path

try:
    from scripts.release_asset_provenance import (
        build_provenance, require, validate_provenance, write_json_atomic,
    )
except ModuleNotFoundError:
    from release_asset_provenance import (
        build_provenance, require, validate_provenance, write_json_atomic,
    )


def verify_saved_assets(directory, original, *, platform, date_tag, release_tag,
                        target_sha, run_id, run_attempt):
    original_attempt = original.get('workflowRunAttempt')
    require(type(original_attempt) is int and 1 <= original_attempt <= run_attempt,
            'Saved build attempt must belong to this run or an earlier attempt')
    identity = dict(platform=platform, date_tag=date_tag, release_tag=release_tag,
                    target_sha=target_sha, run_id=run_id)
    records = validate_provenance(original, **identity, run_attempt=original_attempt)
    require(set(path.name for path in directory.iterdir()) == set(records),
            'Saved release inventory differs from the tested build')
    require(all(not (directory / name).is_symlink() for name in records),
            'Saved release assets must not be symlinks')
    current = build_provenance(directory, **identity, run_attempt=run_attempt)
    require(current['assets'] == original['assets'], 'Saved assets differ from the tested build')
    return current


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, default=Path('dist/release'))
    parser.add_argument('--original', type=Path,
                        default=Path('dist/release-meta/build-release-asset-provenance.json'))
    parser.add_argument('--output', type=Path,
                        default=Path('dist/release-meta/release-asset-provenance.json'))
    args = parser.parse_args()
    require(args.original.is_file() and not args.original.is_symlink()
            and args.original.stat().st_size <= 1_000_000, 'Invalid saved build manifest')
    require(args.original.resolve() != args.output.resolve(), 'Do not overwrite build evidence')
    original = json.loads(args.original.read_text(encoding='utf-8'))
    require(isinstance(original, dict), 'Build evidence must be an object')
    payload = verify_saved_assets(
        args.directory, original, platform='windows',
        date_tag=os.environ['RELEASE_DATE_TAG'], release_tag=os.environ['RELEASE_TAG'],
        target_sha=os.environ['RELEASE_TARGET_SHA'], run_id=int(os.environ['RELEASE_RUN_ID']),
        run_attempt=int(os.environ['RELEASE_RUN_ATTEMPT']))
    write_json_atomic(args.output, payload)
    print('Saved tested Windows artifacts verified; upload attempt provenance written')


if __name__ == '__main__':
    main()
