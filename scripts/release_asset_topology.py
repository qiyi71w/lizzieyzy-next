#!/usr/bin/env python3
"""发布资产拓扑: public GitHub Release assets and their release relationships.

The topology describes a version's release results: each asset's stable identity,
filename rule, owning platform, publishing roles, and workflow unit. It does not
package, hash, or publish those assets.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from enum import Enum
import re
import sys


DATE_TAG_PATTERN = re.compile(r"\d{4}-\d{2}-\d{2}")


class TopologyError(RuntimeError):
    """Unsupported platform or invalid required topology input."""


class Role(Enum):
    RELEASE_NOTES = "release_notes"
    DIRECT_DOWNLOAD = "direct_download"
    PROVENANCE = "provenance"


class FilenameKind(Enum):
    DATE_PREFIXED = "date_prefixed"
    LITERAL = "literal"


@dataclass(frozen=True)
class FilenameRule:
    kind: FilenameKind
    value: str

    def render(self, date_tag: str) -> str:
        require_date_tag(date_tag)
        if self.kind is FilenameKind.LITERAL:
            return self.value
        return f"{date_tag}-{self.value}"


@dataclass(frozen=True)
class ReleaseAsset:
    key: str
    filename: FilenameRule
    platform: str
    roles: frozenset[Role]
    public_order: int
    release_notes_order: int | None = None
    direct_download_order: int | None = None


@dataclass(frozen=True)
class ReleaseUnit:
    platform: str
    publisher_identity: str
    workflow_file: str
    run_name_template: str
    dispatch_inputs: tuple[tuple[str, str], ...]
    assets: tuple[ReleaseAsset, ...]


def require_date_tag(date_tag: str) -> None:
    if DATE_TAG_PATTERN.fullmatch(date_tag) is None:
        raise TopologyError("date-tag must use YYYY-MM-DD")


def _asset(
    key: str,
    name: str,
    platform: str,
    *,
    public: int,
    literal: bool = False,
    notes_table: int | None = None,
    direct: int | None = None,
    notes: bool = False,
) -> ReleaseAsset:
    roles = {Role.PROVENANCE}
    if notes or notes_table is not None:
        roles.add(Role.RELEASE_NOTES)
    if direct is not None:
        roles.add(Role.DIRECT_DOWNLOAD)
    return ReleaseAsset(
        key=key,
        filename=FilenameRule(
            FilenameKind.LITERAL if literal else FilenameKind.DATE_PREFIXED,
            name,
        ),
        platform=platform,
        roles=frozenset(roles),
        public_order=public,
        release_notes_order=notes_table,
        direct_download_order=direct,
    )


_WINDOWS_ASSETS = (
    _asset("windows_opencl_installer", "windows64.opencl.installer.exe", "windows", public=0, notes_table=1, direct=2),
    _asset("windows_opencl_portable", "windows64.opencl.portable.zip", "windows", public=1, notes_table=0, direct=0),
    _asset("windows_nvidia_installer", "windows64.nvidia.installer.exe", "windows", public=2, notes_table=5, direct=6),
    _asset("windows_nvidia_portable", "windows64.nvidia.portable.zip", "windows", public=3, notes_table=4, direct=5),
    _asset(
        "windows_directml_experimental",
        "windows64.experimental.directml.portable.zip",
        "windows",
        public=4,
        notes_table=6,
        direct=7,
    ),
    _asset(
        "windows_openvino_experimental",
        "windows64.experimental.openvino.portable.zip",
        "windows",
        public=5,
        notes_table=7,
        direct=8,
    ),
    _asset(
        "windows_rocm_gfx103x_experimental",
        "windows64.experimental.rocm.gfx103x.portable.zip",
        "windows",
        public=6,
        notes_table=8,
        direct=9,
    ),
    _asset(
        "windows_rocm_gfx110x_experimental",
        "windows64.experimental.rocm.gfx110x.portable.zip",
        "windows",
        public=7,
        notes_table=9,
        direct=10,
    ),
    _asset(
        "windows_rocm_gfx1151_experimental",
        "windows64.experimental.rocm.gfx1151.portable.zip",
        "windows",
        public=8,
        notes_table=10,
        direct=11,
    ),
    _asset(
        "windows_rocm_gfx120x_experimental",
        "windows64.experimental.rocm.gfx120x.portable.zip",
        "windows",
        public=9,
        notes_table=11,
        direct=12,
    ),
    _asset("windows_installer", "windows64.with-katago.installer.exe", "windows", public=10, notes_table=3, direct=4),
    _asset("windows_portable", "windows64.with-katago.portable.zip", "windows", public=11, notes_table=2, direct=3),
    _asset(
        "windows_no_engine_installer",
        "windows64.without.engine.installer.exe",
        "windows",
        public=12,
        notes_table=13,
        direct=16,
    ),
    _asset(
        "windows_no_engine_portable",
        "windows64.without.engine.portable.zip",
        "windows",
        public=13,
        notes_table=12,
        direct=15,
    ),
    _asset("windows_core_update", "windows64.core-update.zip", "windows", public=14, notes=True, direct=1),
    _asset(
        "windows_update_manifest",
        "lizzieyzy-next-update-manifest.json",
        "windows",
        public=15,
        literal=True,
    ),
    _asset(
        "windows_tensorrt_split_001",
        "windows64.nvidia.tensorrt.portable.7z.001",
        "windows",
        public=16,
        notes=True,
        direct=13,
    ),
    _asset(
        "windows_tensorrt_split_002",
        "windows64.nvidia.tensorrt.portable.7z.002",
        "windows",
        public=17,
        notes=True,
        direct=14,
    ),
    _asset("windows_tensorrt_split_readme", "windows64.nvidia.tensorrt.portable.README.txt", "windows", public=18),
    _asset(
        "windows_tensorrt_split_manifest",
        "windows64.nvidia.tensorrt.portable.manifest.json",
        "windows",
        public=19,
    ),
    _asset("windows_tensorrt_split_sha256", "windows64.nvidia.tensorrt.portable.sha256.txt", "windows", public=20),
)

_LINUX_ASSETS = (
    _asset("linux64_opencl", "linux64.opencl.zip", "linux", public=0, notes_table=17, direct=20),
    _asset("linux64_nvidia", "linux64.nvidia.zip", "linux", public=1, notes_table=18, direct=21),
    _asset("linux64", "linux64.with-katago.zip", "linux", public=2, notes_table=16, direct=19),
)

_MAC_AMD64_ASSETS = (
    _asset("mac_amd64", "mac-intel.with-katago.dmg", "mac-amd64", public=0, notes_table=15, direct=18),
)
_MAC_ARM64_ASSETS = (
    _asset("mac_arm64", "mac-apple-silicon.with-katago.dmg", "mac-arm64", public=0, notes_table=14, direct=17),
)

_DISPATCH_INPUTS = (("release_prerelease", "true"),)

_UNITS = (
    ReleaseUnit(
        "windows",
        "Windows",
        "build-windows-release.yml",
        "Windows release {release_tag} | {date_tag} | prerelease={prerelease}",
        _DISPATCH_INPUTS,
        _WINDOWS_ASSETS,
    ),
    ReleaseUnit(
        "linux",
        "Linux",
        "build-linux-release.yml",
        "Linux release {release_tag} | {date_tag} | prerelease={prerelease}",
        _DISPATCH_INPUTS,
        _LINUX_ASSETS,
    ),
    ReleaseUnit(
        "mac-amd64",
        "macOS Intel",
        "build-macos-amd64-release.yml",
        "macOS Intel release {release_tag} | {date_tag} | prerelease={prerelease}",
        _DISPATCH_INPUTS,
        _MAC_AMD64_ASSETS,
    ),
    ReleaseUnit(
        "mac-arm64",
        "macOS Apple Silicon",
        "build-macos-arm64-release.yml",
        "macOS Apple Silicon release {release_tag} | {date_tag} | prerelease={prerelease}",
        _DISPATCH_INPUTS,
        _MAC_ARM64_ASSETS,
    ),
)


def platforms() -> tuple[str, ...]:
    return tuple(unit.platform for unit in _UNITS)


def release_units() -> tuple[ReleaseUnit, ...]:
    return _UNITS


def release_unit(platform: str) -> ReleaseUnit:
    for unit in _UNITS:
        if unit.platform == platform:
            return unit
    raise TopologyError(f"Unsupported platform: {platform}")


def assets() -> tuple[ReleaseAsset, ...]:
    return tuple(asset for unit in _UNITS for asset in unit.assets)


def asset(key: str) -> ReleaseAsset:
    for item in assets():
        if item.key == key:
            return item
    raise TopologyError(f"Unknown release asset: {key}")


def public_inventory(platform: str, date_tag: str) -> tuple[str, ...]:
    unit = release_unit(platform)
    return tuple(item.filename.render(date_tag) for item in unit.assets)


def provenance_names(platform: str, date_tag: str) -> tuple[str, ...]:
    return tuple(sorted(public_inventory(platform, date_tag)))


def direct_download_names(date_tag: str) -> tuple[str, ...]:
    ordered = sorted(
        (item for item in assets() if Role.DIRECT_DOWNLOAD in item.roles),
        key=lambda item: item.direct_download_order or 0,
    )
    return tuple(item.filename.render(date_tag) for item in ordered)


def release_notes_table_assets() -> tuple[ReleaseAsset, ...]:
    return tuple(
        sorted(
            (item for item in assets() if item.release_notes_order is not None),
            key=lambda item: item.release_notes_order or 0,
        )
    )


def release_notes_assets() -> tuple[ReleaseAsset, ...]:
    table = release_notes_table_assets()
    extras = tuple(
        item
        for item in assets()
        if Role.RELEASE_NOTES in item.roles and item.release_notes_order is None
    )
    return table + extras


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    expected = subparsers.add_parser(
        "expected-names",
        help="Print public inventory filenames, one per line, in topology order",
    )
    expected.add_argument("--platform", required=True)
    expected.add_argument("--date-tag", required=True)
    args = parser.parse_args(argv)
    try:
        for name in public_inventory(args.platform, args.date_tag):
            print(name)
    except TopologyError as exc:
        print(str(exc), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
