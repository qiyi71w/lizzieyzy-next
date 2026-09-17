#!/usr/bin/env python3
"""Prepare the catalog-pinned Linux Eigen KataGo acceptance closure."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import urllib.request
import uuid
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO, Callable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CATALOG = ROOT / "src" / "main" / "resources" / "katago-assets.json"
ARCHIVE_RELEASE_BASE = "https://github.com/lightvector/KataGo/releases/download"
SHA256_LENGTH = 64


class ProvisioningError(RuntimeError):
    """The pinned acceptance closure could not be prepared safely."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ProvisioningError(f"{label} must be an object")
    return value


def require_text(value: dict[str, Any], key: str, label: str) -> str:
    result = value.get(key)
    if not isinstance(result, str) or not result.strip():
        raise ProvisioningError(f"{label} is missing non-empty {key}")
    return result.strip()


def require_size(value: dict[str, Any], label: str) -> int:
    result = value.get("sizeBytes")
    if not isinstance(result, int) or isinstance(result, bool) or result <= 0:
        raise ProvisioningError(f"{label} has invalid sizeBytes")
    return result


def require_sha256(value: dict[str, Any], label: str) -> str:
    result = require_text(value, "sha256", label)
    if len(result) != SHA256_LENGTH or any(
        character not in "0123456789abcdef" for character in result
    ):
        raise ProvisioningError(f"{label} has invalid sha256")
    return result


def load_pins(path: Path) -> dict[str, Any]:
    try:
        catalog = require_object(json.loads(path.read_text(encoding="utf-8")), "catalog")
    except (OSError, json.JSONDecodeError) as failure:
        raise ProvisioningError(f"cannot read catalog {path}: {failure}") from failure
    if catalog.get("schemaVersion") != 1:
        raise ProvisioningError("KataGo asset catalog schemaVersion must be 1")

    version = require_text(catalog, "katagoVersion", "catalog")
    release_tag = require_text(catalog, "katagoReleaseTag", "catalog")
    if release_tag != f"v{version}":
        raise ProvisioningError("katagoReleaseTag must match katagoVersion")
    require_text(catalog, "katagoSourceCommit", "catalog")
    require_text(catalog, "modelReleaseTag", "catalog")

    assets = require_object(catalog.get("assets"), "catalog assets")
    asset = require_object(assets.get("linux-cpu"), "catalog assets.linux-cpu")
    if require_text(asset, "platform", "linux-cpu asset") != "linux-x64":
        raise ProvisioningError("linux-cpu asset platform must be linux-x64")
    if require_text(asset, "backend", "linux-cpu asset") != "eigen":
        raise ProvisioningError("linux-cpu asset backend must be eigen")
    archive_name = require_text(asset, "assetName", "linux-cpu asset")
    if f"-{release_tag}-" not in archive_name or not archive_name.endswith(".zip"):
        raise ProvisioningError("linux-cpu assetName does not match the pinned release")
    require_size(asset, "linux-cpu asset")
    require_sha256(asset, "linux-cpu asset")

    models = require_object(catalog.get("models"), "catalog models")
    model_id = require_text(catalog, "defaultModelId", "catalog")
    model = require_object(models.get(model_id), f"catalog model {model_id}")
    require_text(model, "fileName", f"model {model_id}")
    require_text(model, "minimumKataGoVersion", f"model {model_id}")
    require_size(model, f"model {model_id}")
    require_sha256(model, f"model {model_id}")
    if model.get("bundled") is not True:
        raise ProvisioningError("default model must be marked bundled")
    return catalog


def verify_file(path: Path, expected_size: int, expected_sha256: str, label: str) -> None:
    if not path.is_file():
        raise ProvisioningError(f"{label} is missing: {path}")
    actual_size = path.stat().st_size
    if actual_size != expected_size:
        raise ProvisioningError(
            f"{label} size mismatch: expected {expected_size}, got {actual_size}"
        )
    actual_sha256 = sha256_file(path)
    if actual_sha256 != expected_sha256:
        raise ProvisioningError(
            f"{label} SHA-256 mismatch: expected {expected_sha256}, got {actual_sha256}"
        )


def model_download_url(catalog: dict[str, Any], model: dict[str, Any]) -> str:
    override = model.get("downloadUrl")
    if isinstance(override, str) and override.strip():
        return override.strip()
    return (
        f"{ARCHIVE_RELEASE_BASE}/{catalog['modelReleaseTag']}/"
        f"{model['fileName']}"
    )


def open_url(url: str) -> BinaryIO:
    request = urllib.request.Request(
        url, headers={"User-Agent": "lizzieyzy-next-acceptance/1"}
    )
    return urllib.request.urlopen(request)


def download_verified(
    url: str,
    destination: Path,
    expected_size: int,
    expected_sha256: str,
    label: str,
    opener: Callable[[str], BinaryIO],
) -> None:
    part = destination.with_name(destination.name + ".part")
    part.unlink(missing_ok=True)
    try:
        with opener(url) as response, part.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)
        verify_file(part, expected_size, expected_sha256, label)
        os.replace(part, destination)
    except ProvisioningError:
        part.unlink(missing_ok=True)
        raise
    except Exception as failure:
        part.unlink(missing_ok=True)
        raise ProvisioningError(f"failed to download {label} from {url}: {failure}") from failure


def obtain_cached(
    cache: Path,
    file_name: str,
    url: str,
    expected_size: int,
    expected_sha256: str,
    label: str,
    opener: Callable[[str], BinaryIO],
) -> tuple[Path, bool]:
    path = cache / file_name
    if path.exists():
        verify_file(path, expected_size, expected_sha256, f"cached {label}")
        return path, False
    download_verified(url, path, expected_size, expected_sha256, label, opener)
    return path, True


def validate_archive_entries(archive: zipfile.ZipFile) -> None:
    for entry in archive.infolist():
        name = entry.filename.replace("\\", "/")
        pure = PurePosixPath(name)
        file_type = (entry.external_attr >> 16) & stat.S_IFMT(0o170000)
        if (
            not name
            or pure.is_absolute()
            or ".." in pure.parts
            or any(part in ("", ".") for part in pure.parts)
            or file_type == stat.S_IFLNK
        ):
            raise ProvisioningError(f"unsafe archive entry: {entry.filename}")


def extract_archive(archive_path: Path, destination: Path) -> None:
    try:
        with zipfile.ZipFile(archive_path) as archive:
            validate_archive_entries(archive)
            archive.extractall(destination)
    except ProvisioningError:
        raise
    except (OSError, zipfile.BadZipFile) as failure:
        raise ProvisioningError(f"invalid KataGo archive {archive_path}: {failure}") from failure


def find_unique(root: Path, name: str) -> Path:
    matches = [path for path in root.rglob(name) if path.is_file()]
    if len(matches) != 1:
        raise ProvisioningError(
            f"archive must contain exactly one {name}; found {len(matches)}"
        )
    return matches[0]


def run_version(executable: Path) -> str:
    try:
        result = subprocess.run(
            [str(executable), "version"],
            check=True,
            capture_output=True,
            text=True,
            timeout=15,
        )
    except (OSError, subprocess.SubprocessError) as failure:
        raise ProvisioningError(f"failed to run {executable} version: {failure}") from failure
    return (result.stdout + result.stderr).strip()


def file_identity(path: Path) -> dict[str, Any]:
    return {
        "path": str(path.resolve()),
        "sizeBytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def write_manifest_atomic(path: Path, manifest: dict[str, Any]) -> None:
    part = path.with_name(path.name + ".part")
    part.unlink(missing_ok=True)
    try:
        with part.open("x", encoding="utf-8") as output:
            json.dump(manifest, output, indent=2, sort_keys=True)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(part, path)
    except Exception:
        part.unlink(missing_ok=True)
        raise


def prepare(
    root: Path,
    *,
    catalog_path: Path = DEFAULT_CATALOG,
    opener: Callable[[str], BinaryIO] = open_url,
    version_runner: Callable[[Path], str] = run_version,
) -> Path:
    root = root.expanduser().resolve()
    catalog_path = catalog_path.expanduser().resolve()
    if sys.platform != "linux" or os.uname().machine not in ("x86_64", "amd64"):
        raise ProvisioningError("CPU acceptance provisioning requires Linux x64")
    root.mkdir(parents=True, exist_ok=True)
    manifest_path = root / "manifest.json"
    if manifest_path.exists():
        raise ProvisioningError(f"output root already contains a manifest: {manifest_path}")
    cache = root / "cache"
    cache.mkdir(exist_ok=True)

    catalog = load_pins(catalog_path)
    asset = catalog["assets"]["linux-cpu"]
    model_id = catalog["defaultModelId"]
    model = catalog["models"][model_id]
    archive_name = asset["assetName"]
    archive_url = f"{ARCHIVE_RELEASE_BASE}/{catalog['katagoReleaseTag']}/{archive_name}"
    archive_path, archive_downloaded = obtain_cached(
        cache,
        archive_name,
        archive_url,
        asset["sizeBytes"],
        asset["sha256"],
        "Linux Eigen archive",
        opener,
    )
    model_path, model_downloaded = obtain_cached(
        cache,
        model["fileName"],
        model_download_url(catalog, model),
        model["sizeBytes"],
        model["sha256"],
        "default model",
        opener,
    )

    identifier = uuid.uuid4().hex
    staging = root / f".runtime-{identifier}.part"
    runtime = root / f"runtime-{identifier}"
    published = False
    try:
        staging.mkdir()
        archive_root = staging / "archive"
        archive_root.mkdir()
        extract_archive(archive_path, archive_root)
        executable = find_unique(archive_root, "katago")
        config = find_unique(archive_root, "default_gtp.cfg")
        executable.chmod(executable.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        if not os.access(executable, os.X_OK):
            raise ProvisioningError(f"extracted KataGo is not executable: {executable}")
        if config.stat().st_size == 0:
            raise ProvisioningError("extracted default_gtp.cfg is empty")

        runtime_model = staging / "model" / model["fileName"]
        runtime_model.parent.mkdir()
        shutil.copyfile(model_path, runtime_model)
        verify_file(
            runtime_model,
            model["sizeBytes"],
            model["sha256"],
            "prepared default model",
        )
        version_output = version_runner(executable).strip()
        expected_version = f"KataGo v{catalog['katagoVersion']}"
        if expected_version not in version_output:
            raise ProvisioningError(
                f"unexpected KataGo version output: expected {expected_version}, got {version_output!r}"
            )
        if "Using Eigen(CPU) backend" not in version_output:
            raise ProvisioningError(
                f"unexpected KataGo backend output: expected Eigen(CPU), got {version_output!r}"
            )

        relative_executable = executable.relative_to(staging)
        relative_config = config.relative_to(staging)
        relative_model = runtime_model.relative_to(staging)
        os.replace(staging, runtime)
        published = True
        executable = runtime / relative_executable
        config = runtime / relative_config
        runtime_model = runtime / relative_model

        manifest = {
            "schemaVersion": 1,
            "catalog": {
                "path": str(catalog_path),
                "sha256": sha256_file(catalog_path),
            },
            "katago": {
                "version": catalog["katagoVersion"],
                "releaseTag": catalog["katagoReleaseTag"],
                "sourceCommit": catalog["katagoSourceCommit"],
                "backend": asset["backend"],
                "archive": {
                    "fileName": archive_name,
                    "path": str(archive_path.resolve()),
                    "sizeBytes": asset["sizeBytes"],
                    "sha256": asset["sha256"],
                },
                "executable": {
                    **file_identity(executable),
                    "versionOutput": version_output,
                },
            },
            "model": {
                "id": model_id,
                "fileName": model["fileName"],
                "minimumKataGoVersion": model["minimumKataGoVersion"],
                **file_identity(runtime_model),
            },
            "config": {
                "sourceArchiveEntry": "default_gtp.cfg",
                **file_identity(config),
            },
            "preparedAt": datetime.now(timezone.utc).isoformat(),
            "networkUsed": archive_downloaded or model_downloaded,
        }
        write_manifest_atomic(manifest_path, manifest)
        return manifest_path
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        if published and not manifest_path.exists():
            shutil.rmtree(runtime, ignore_errors=True)
        raise


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare the catalog-pinned Linux Eigen KataGo acceptance closure."
    )
    parser.add_argument("--root", type=Path, required=True, help="Fresh output/cache root")
    parser.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        manifest = prepare(args.root, catalog_path=args.catalog)
    except ProvisioningError as failure:
        print(f"error: {failure}", file=sys.stderr)
        return 1
    print(manifest)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
