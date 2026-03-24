#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[1]
VERSION_FILE = ROOT / 'engines' / 'katago' / 'VERSION.txt'

ASSET_SPECS = [
    ('windows_installer', 'windows64.with-katago.installer.exe', 'Windows 64 位', 'Windows x64'),
    ('windows_portable', 'windows64.with-katago.portable.zip', 'Windows 64 位，想免安装', 'Windows x64, no installer'),
    ('windows_no_engine_installer', 'windows64.without.engine.installer.exe', 'Windows 64 位，想自己配引擎，也想安装器', 'Windows x64, your own engine with installer'),
    ('windows_no_engine_portable', 'windows64.without.engine.portable.zip', 'Windows 64 位，想自己配引擎', 'Windows x64, your own engine'),
    ('mac_arm64', 'mac-arm64.with-katago.dmg', 'macOS Apple Silicon', 'macOS Apple Silicon'),
    ('mac_amd64', 'mac-amd64.with-katago.dmg', 'macOS Intel', 'macOS Intel'),
    ('linux64', 'linux64.with-katago.zip', 'Linux 64 位', 'Linux x64'),
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description='Generate polished multi-language GitHub release notes.')
    parser.add_argument('--date-tag', help='Release date tag, for example 2026-03-23')
    parser.add_argument('--release-dir', default=str(ROOT / 'dist' / 'release'), help='Directory containing release assets')
    parser.add_argument('--release-tag', help='GitHub release tag, used for direct asset links')
    parser.add_argument('--repo', default='wimi321/lizzieyzy-next', help='GitHub repo in owner/name format')
    parser.add_argument('--from-gh', action='store_true', help='Read asset names from GitHub release instead of local dist/release')
    parser.add_argument('--output', help='Output markdown file path; defaults to stdout')
    return parser.parse_args()


def load_bundle_metadata() -> dict[str, str]:
    metadata = {
        'katago_version': 'Unknown',
        'model_source': 'Unknown',
    }
    if not VERSION_FILE.exists():
        return metadata

    for raw_line in VERSION_FILE.read_text(encoding='utf-8').splitlines():
        if ':' not in raw_line:
            continue
        key, value = raw_line.split(':', 1)
        key = key.strip().lower()
        value = value.strip()
        if key == 'katago release':
            metadata['katago_version'] = value
        elif key == 'model source':
            metadata['model_source'] = value
    return metadata


def run_command(cmd: list[str]) -> str:
    result = subprocess.run(cmd, check=True, capture_output=True, text=True)
    return result.stdout


def asset_names_from_gh(repo: str, release_tag: str) -> list[str]:
    if not release_tag:
        raise SystemExit('--release-tag is required when --from-gh is used')
    payload = run_command(['gh', 'release', 'view', release_tag, '--repo', repo, '--json', 'assets'])
    data = json.loads(payload)
    return [asset['name'] for asset in data.get('assets', [])]


def asset_names_from_dir(release_dir: str, date_tag: str | None) -> list[str]:
    path = Path(release_dir)
    if not path.is_dir():
        raise SystemExit(f'Release directory not found: {path}')
    names = [item.name for item in path.iterdir() if item.is_file()]
    if date_tag:
        dated = [name for name in names if name.startswith(f'{date_tag}-')]
        if dated:
            return dated
    return names


def pick_asset(asset_names: list[str], suffix: str, date_tag: str | None) -> str | None:
    matches = [name for name in asset_names if name.endswith(suffix)]
    if date_tag:
        dated = [name for name in matches if name.startswith(f'{date_tag}-')]
        if dated:
            matches = dated
    return sorted(matches)[-1] if matches else None


def release_asset_url(repo: str, release_tag: str | None, asset_name: str) -> str | None:
    if not release_tag:
        return None
    return f'https://github.com/{repo}/releases/download/{quote(release_tag)}/{quote(asset_name)}'


def format_asset(asset_name: str | None, repo: str, release_tag: str | None) -> str:
    if not asset_name:
        return '暂未包含在本次发布中'
    url = release_asset_url(repo, release_tag, asset_name)
    if not url:
        return f'`{asset_name}`'
    return f'[`{asset_name}`]({url})'


def format_asset_en(asset_name: str | None, repo: str, release_tag: str | None) -> str:
    if not asset_name:
        return 'Not included in this release'
    url = release_asset_url(repo, release_tag, asset_name)
    if not url:
        return f'`{asset_name}`'
    return f'[`{asset_name}`]({url})'


def build_release_notes(asset_map: dict[str, str | None], bundle: dict[str, str], repo: str, release_tag: str | None) -> str:
    windows_installer = format_asset(asset_map['windows_installer'], repo, release_tag)
    windows_portable = format_asset(asset_map['windows_portable'], repo, release_tag)
    windows_no_engine_installer = format_asset(asset_map['windows_no_engine_installer'], repo, release_tag)
    windows_no_engine_portable = format_asset(asset_map['windows_no_engine_portable'], repo, release_tag)
    mac_arm64 = format_asset(asset_map['mac_arm64'], repo, release_tag)
    mac_amd64 = format_asset(asset_map['mac_amd64'], repo, release_tag)
    linux64 = format_asset(asset_map['linux64'], repo, release_tag)

    windows_installer_en = format_asset_en(asset_map['windows_installer'], repo, release_tag)
    windows_no_engine_installer_en = format_asset_en(asset_map['windows_no_engine_installer'], repo, release_tag)
    windows_no_engine_portable_en = format_asset_en(asset_map['windows_no_engine_portable'], repo, release_tag)
    mac_arm64_en = format_asset_en(asset_map['mac_arm64'], repo, release_tag)
    mac_amd64_en = format_asset_en(asset_map['mac_amd64'], repo, release_tag)
    linux64_en = format_asset_en(asset_map['linux64'], repo, release_tag)

    katago_version = bundle['katago_version']
    model_source = bundle['model_source']

    return f"""# LizzieYzy Next

## 中文

原版 `lizzieyzy` 很多人已经没法正常同步野狐棋谱了。这个维护版把最常用的那条链路重新做回可用：下载安装，输入 **野狐昵称**，继续抓谱、分析、复盘。

### 先看这几句

- Windows 普通用户直接下载 {windows_installer}
- 抓谱时直接输入 **野狐昵称**，程序会自动匹配账号并获取最近公开棋谱
- 第一次启动会优先自动准备分析环境
- 主整合包已经内置 KataGo `{katago_version}` 和默认权重 `{model_source}`

### 下载建议

| 你的电脑 | 直接下载这个 |
| --- | --- |
| Windows 64 位 | {windows_installer} |
| Windows 64 位，想免安装 | {windows_portable} |
| Windows 64 位，想自己配引擎，也想安装器 | {windows_no_engine_installer} |
| Windows 64 位，想自己配引擎 | {windows_no_engine_portable} |
| macOS Apple Silicon | {mac_arm64} |
| macOS Intel | {mac_amd64} |
| Linux 64 位 | {linux64} |

### 这次发布值得先看的地方

- 原版已经失效的野狐棋谱同步，现在重新可用
- 现在统一改成“野狐昵称”输入路径，普通用户更容易直接上手
- Windows 继续把安装器放在最前面，下载后更容易直接开始用
- macOS 继续提供 Apple Silicon / Intel 两种 `.dmg`
- 整合包继续内置 KataGo 与默认权重，安装后可以更快开始分析

## English

This maintained fork restores the broken Fox public-game fetch path for LizzieYzy and keeps the common review workflow usable.

- Windows first choice: {windows_installer_en}
- Windows custom-engine installer: {windows_no_engine_installer_en}
- Windows custom-engine portable: {windows_no_engine_portable_en}
- Fox fetch now starts from a **Fox nickname** and resolves the matching account automatically.
- First launch tries to prepare the bundled analysis setup automatically.
- Bundled packages include KataGo `{katago_version}` and the default weight `{model_source}`.
- macOS downloads: Apple Silicon {mac_arm64_en}, Intel {mac_amd64_en}
- Linux download: {linux64_en}

## 日本語

このメンテ版は、元の `lizzieyzy` で使えなくなっていた野狐棋譜取得の流れを、もう一度使える状態に戻すための継続保守版です。

- Windows 利用者の多くは {windows_installer_en} を選べば始めやすいです
- 自分のエンジンを使いたい場合は {windows_no_engine_installer_en} または {windows_no_engine_portable_en} を選べます
- 棋譜取得では **野狐のニックネーム** を入力します。アプリが一致するアカウントを自動で探します
- 初回起動では、内蔵の解析環境を自動で準備する流れを優先します
- 主な整合パッケージには KataGo `{katago_version}` と既定の重み `{model_source}` が含まれています

## 한국어

이 유지보수판은 원래 `lizzieyzy` 에서 더 이상 잘 동작하지 않던 Fox 공개 기보 가져오기를 다시 쓸 수 있게 만든 지속 유지보수 포크입니다.

- 대부분의 Windows 사용자는 {windows_installer_en} 를 먼저 받으면 가장 쉽습니다
- 직접 엔진을 쓰고 싶다면 {windows_no_engine_installer_en} 또는 {windows_no_engine_portable_en} 를 고를 수 있습니다
- 기보를 가져올 때는 **Fox 닉네임** 을 입력하면 앱이 맞는 계정을 자동으로 찾아 줍니다
- 첫 실행에서는 내장 분석 환경을 자동으로 준비하는 흐름을 먼저 시도합니다
- 주요 통합 패키지에는 KataGo `{katago_version}` 와 기본 가중치 `{model_source}` 가 포함되어 있습니다
"""


def main() -> int:
    args = parse_args()
    if args.from_gh:
        asset_names = asset_names_from_gh(args.repo, args.release_tag)
    else:
        asset_names = asset_names_from_dir(args.release_dir, args.date_tag)

    asset_map = {
        key: pick_asset(asset_names, suffix, args.date_tag)
        for key, suffix, _cn, _en in ASSET_SPECS
    }
    bundle = load_bundle_metadata()
    notes = build_release_notes(asset_map, bundle, args.repo, args.release_tag)

    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(notes, encoding='utf-8')
    else:
        sys.stdout.write(notes)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
