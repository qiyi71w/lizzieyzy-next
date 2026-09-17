#!/usr/bin/env python3
"""Check source identities and every original file before platform signing/repackaging."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

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


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    parser.add_argument("--target", choices=TARGETS, required=True)
    parser.add_argument("--engine", type=Path, required=True)
    args = parser.parse_args()
    audit(load_catalog(args.catalog), args.target, args.engine)
