#!/usr/bin/env python3
"""Check source identities and every original file before platform signing/repackaging."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import shutil
import tempfile

from build_katago_source import SOURCE_COMMIT, TARGETS
from katago_asset_catalog import DEFAULT_CATALOG, load_catalog
from stage_katago_source_release import digest, normalized_name


def audit(catalog: dict, target: str, engine: Path) -> None:
    if catalog.get("origin", "official-release") == "official-release":
        return
    asset = catalog["assets"][target]
    metadata = json.loads((engine / "source-release.json").read_text(encoding="utf-8"))
    executable = "katago.exe" if target.startswith("windows-") else "katago"
    if (metadata.get("sourceCommit") != SOURCE_COMMIT
            or catalog.get("katagoSourceCommit") != SOURCE_COMMIT
            or metadata.get("target") != target or metadata.get("backend") != TARGETS[target][2]
            or metadata.get("origin") != "project-source-build"
            or metadata.get("executable", {}).get("sha256") != asset["executableSha256"]):
        raise ValueError("installed source identity differs from trusted catalog")
    for key in ("buildStatus", "packagingStatus", "dependencyAuditStatus"):
        if metadata.get(key) != "PASS":
            raise ValueError("installed source build did not pass required gates")
    records = metadata.get("files", [])
    names = [normalized_name(record.get("file")) for record in records]
    if (len({name.casefold() for name in names}) != len(names)
            or not {executable, "default_gtp.cfg", "analysis_example.cfg"}.issubset(names)
            or not any(name.startswith("licenses/") for name in names)):
        raise ValueError("installed source inventory is incomplete or ambiguous")
    for record, name in zip(records, names):
        path = engine.joinpath(*name.split("/"))
        if (path.is_symlink() or not path.is_file() or engine.resolve() not in path.resolve().parents
                or path.stat().st_size != record.get("sizeBytes") or digest(path) != record.get("sha256")):
            raise ValueError(f"installed source file missing or modified: {name}")
    if digest(engine / executable) != asset["executableSha256"]:
        raise ValueError("installed engine executable differs from trusted catalog")
    print(f"{target}: exact source {SOURCE_COMMIT}, {len(records)} files verified")


def restore_after_jpackage(catalog: dict, target: str, source: Path, engine: Path) -> None:
    """Undo jpackage's implicit ad-hoc signing, before our platform signing stage."""
    if catalog.get("origin") != "project-source-build" or target not in ("macos-arm64", "macos-amd64"):
        raise ValueError("restoration is only for reviewed macOS source bundles")
    if (engine.is_symlink() or not engine.is_dir() or engine.name != target
            or engine.resolve() == source.resolve()
            or source.resolve() in engine.resolve().parents
            or engine.resolve() in source.resolve().parents):
        raise ValueError("restoration requires separate, existing build directories")
    audit(catalog, target, source)
    with tempfile.TemporaryDirectory(prefix=".reviewed-katago-", dir=engine.parent) as temporary:
        staged = Path(temporary) / target
        shutil.copytree(source, staged, symlinks=True)
        audit(catalog, target, staged)
        previous = Path(temporary) / "previous"
        engine.rename(previous)
        try:
            staged.rename(engine)
        except OSError:
            previous.rename(engine)
            raise
    audit(catalog, target, engine)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--engine", type=Path, required=True)
    parser.add_argument("--restore-from", type=Path,
                        help="Restore verified macOS input after jpackage, before platform signing")
    args = parser.parse_args()
    catalog = load_catalog(args.catalog)
    if args.restore_from is not None:
        restore_after_jpackage(catalog, args.target, args.restore_from, args.engine)
    else:
        audit(catalog, args.target, args.engine)
