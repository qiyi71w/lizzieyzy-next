#!/usr/bin/env python3
"""Install verified source release archives into a build tree, never fall back to old engines."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

from katago_asset_catalog import asset_download_url, load_catalog
from stage_katago_source_release import digest, normalized_name, verify_archive

DESTINATIONS = {
    "windows-cpu": "windows-x64", "windows-opencl": "windows-x64-opencl",
    "windows-nvidia": "windows-x64-nvidia", "windows-tensorrt": "windows-x64-nvidia-tensorrt",
    "windows-directml": "windows-x64-directml", "windows-openvino": "windows-x64-openvino",
    **{f"windows-rocm-{family}": f"windows-x64-rocm-{family}"
       for family in ("gfx103x", "gfx110x", "gfx1151", "gfx120x")},
    "linux-cpu": "linux-x64", "linux-opencl": "linux-x64-opencl", "linux-nvidia": "linux-x64-nvidia",
    "macos-arm64": "macos-arm64", "macos-amd64": "macos-amd64",
}


def download(catalog: dict, target: str, cache: Path) -> Path:
    asset = catalog["assets"][target]
    path = cache / asset["assetName"]
    if path.is_file() and path.stat().st_size == asset["sizeBytes"] and digest(path) == asset["sha256"]:
        return path
    cache.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".source-download-", dir=cache) as temporary:
        pending = Path(temporary) / asset["assetName"]
        if os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN"):
            # gh handles authenticated Draft assets without forwarding a token to storage/CDN hosts.
            subprocess.run(["gh", "release", "download", catalog["engineReleaseTag"],
                            "--repo", catalog["engineReleaseRepository"], "--pattern", asset["assetName"],
                            "--dir", temporary], check=True, timeout=1800)
        else:
            subprocess.run(["curl", "--fail", "--location", "--proto", "=https", "--proto-redir", "=https",
                            "--retry", "3", "--connect-timeout", "30", "--max-time", "1800",
                            "--output", str(pending), asset_download_url(catalog, target)], check=True)
        if pending.stat().st_size != asset["sizeBytes"] or digest(pending) != asset["sha256"]:
            raise ValueError(f"{target}: source archive size or checksum mismatch")
        pending.replace(path)
    return path


def unpack(archive: Path, output: Path, target: str, asset: dict) -> dict:
    if archive.stat().st_size != asset["sizeBytes"] or digest(archive) != asset["sha256"]:
        raise ValueError("untrusted source archive")
    metadata = verify_archive(archive, target)
    if metadata["executable"]["sha256"] != asset["executableSha256"]:
        raise ValueError("source executable does not match the trusted catalog")
    output.mkdir(parents=True, exist_ok=False)
    with zipfile.ZipFile(archive) as opened:
        for item in opened.infolist():
            name = normalized_name(item.filename)
            path = output.joinpath(*name.split("/"))
            path.parent.mkdir(parents=True, exist_ok=True)
            with opened.open(item) as source, path.open("xb") as destination:
                shutil.copyfileobj(source, destination, 1024 * 1024)
            path.chmod((item.external_attr >> 16) & 0o777)
    return metadata


def prepare(catalog_path: Path, targets: list[str], cache: Path, engines: Path) -> None:
    catalog = load_catalog(catalog_path)
    if catalog.get("origin") != "project-source-build":
        raise ValueError("source preparation requires the reviewed source catalog")
    if not targets or len(set(targets)) != len(targets) or any(target not in DESTINATIONS for target in targets):
        raise ValueError("invalid or duplicate source preparation targets")
    if engines.is_symlink() or (engines / "configs").is_symlink():
        raise ValueError("build engine/config roots cannot be symlinks")
    if any((engines / DESTINATIONS[target]).is_symlink() for target in targets):
        raise ValueError("build engine destinations cannot be symlinks")
    engines.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".source-assets-", dir=engines.parent) as temporary:
        staging = Path(temporary)
        for target in targets:
            archive = download(catalog, target, cache)
            output = staging / DESTINATIONS[target]
            metadata = unpack(archive, output, target, catalog["assets"][target])
            backend = catalog["assets"][target]["backend"]
            if target.startswith("windows-rocm-"):
                backend = "rocm-" + catalog["assets"][target]["gpuFamily"].lower()
            if catalog["assets"][target]["releaseTier"] == "experimental":
                (output / "lizzieyzy-next-engine-backend.txt").write_text(backend + "\n", encoding="utf-8")
            (output / "lizzieyzy-next-katago-engine-manifest.txt").write_text(
                f"KataGo release: {catalog['katagoReleaseTag']}\n"
                f"Asset: {catalog['assets'][target]['assetName']}\n"
                f"Asset SHA-256: {catalog['assets'][target]['sha256']}\n"
                f"Backend: {backend}\nOrigin: project-source-build\nSource commit: {metadata['sourceCommit']}\n",
                encoding="utf-8")
        config_source = staging / DESTINATIONS[targets[0]]
        configurations = {"gtp.cfg": config_source / "default_gtp.cfg",
                          "analysis.cfg": config_source / "analysis_example.cfg"}
        if not all(path.is_file() for path in configurations.values()):
            raise ValueError("source archives did not include required configurations")
        # Only after every requested target passed verification may the build tree change.
        for target in targets:
            destination = engines / DESTINATIONS[target]
            if destination.exists():
                shutil.rmtree(destination)
            shutil.copytree(staging / DESTINATIONS[target], destination)
        (engines / "configs").mkdir(exist_ok=True)
        for name, path in configurations.items():
            shutil.copy2(path, engines / "configs" / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--targets", nargs="+", required=True, choices=DESTINATIONS)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--engines", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.catalog, args.targets, args.cache, args.engines)
