<p align="center">
  <img src="assets/hero.svg" alt="LizzieYzy Next-FoxUID" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next-foxuid?display_name=tag&label=Release&color=1B4D3E" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/actions/workflows/ci.yml"><img src="https://github.com/wimi321/lizzieyzy-next-foxuid/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next-foxuid?style=flat&color=7F4F24" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next-foxuid/total?label=Downloads&color=2F4858" alt="Downloads"></a>
  <a href="LICENSE.txt"><img src="https://img.shields.io/badge/License-GPL%20v3-E7A23B" alt="License"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-4A5D23" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">中文</a> · English · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a>
</p>

<p align="center">
  <strong>A maintained LizzieYzy fork focused on making the broken Fox sync usable again.</strong><br/>
  The workflow now centers on <strong>numeric Fox ID</strong>: digits only, not a nickname, plus first-launch auto setup, bundled KataGo packages, and clearer multi-platform releases.
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next-foxuid/releases">Download Releases</a>
  ·
  <a href="#what-to-download-first">What To Download</a>
  ·
  <a href="#three-minute-setup">Three-Minute Setup</a>
  ·
  <a href="#release-assets">Release Assets</a>
  ·
  <a href="#docs-and-support">Docs & Support</a>
</p>

> [!IMPORTANT]
> If you just want the app to work after download, remember these 3 things:
> - Windows users should start with `windows64.with-katago.installer.exe`
> - Fox kifu fetch now expects a **numeric Fox ID**: digits only, no nickname
> - First launch tries to auto-configure bundled KataGo, weights, and engine paths

## What To Download First

| If you are on | Download this first | Best for |
| --- | --- | --- |
| Windows x64 and want the easiest path | `windows64.with-katago.installer.exe` | Double-click install, launch, start reviewing |
| Windows x64 and prefer no installer | `windows64.with-katago.portable.zip` | Unzip and run the packaged app directly |
| Windows x64 and want your own engine | `windows64.without.engine.portable.zip` | Keep the app runtime and configure KataGo yourself |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | M-series Macs |
| macOS Intel | `mac-amd64.with-katago.dmg` | Intel Macs |
| Linux x64 | `linux64.with-katago.zip` | Fastest Linux desktop path |

> [!TIP]
> The maintained public release page now keeps only the 6 primary user-facing assets in the main recommendation list. If older tags still show compatibility packages, treat them as historical assets rather than the main path.

> [!NOTE]
> If you are not sure what to pick:
> - Windows: choose `windows64.with-katago.installer.exe`
> - Mac: choose the `.dmg` that matches your CPU
> - Linux: choose `linux64.with-katago.zip`

## What This Fork Fixes

| User problem | What this fork changes |
| --- | --- |
| Fox sync in the original project stopped working for many users | Restores the public-game fetch flow around numeric Fox ID |
| Users do not know what UID means and may type a nickname | UI and docs now say **numeric Fox ID** and explicitly say digits only |
| Windows launch felt too technical | The main recommendation is now `.installer.exe`, with portable `.exe` builds still available |
| First launch often turned into engine setup work | Bundled KataGo, weights, and paths are auto-configured first |
| Release assets were hard to choose | The public release page is centered on 6 primary packages |

## Three-Minute Setup

1. Go to [Releases](https://github.com/wimi321/lizzieyzy-next-foxuid/releases) and choose the package for your system.
2. Windows users should start with `windows64.with-katago.installer.exe`; macOS users should pick the correct `.dmg`; Linux users should choose `linux64.with-katago.zip`.
3. On first launch, the app now tries to auto-detect bundled KataGo, configs, and the default weight.
4. Open **Fox Kifu (Fetch by numeric Fox ID)** and enter a numeric Fox ID. Digits only, not a nickname.
5. Fetch the latest public games and continue with bundled or custom KataGo review.

## What First Launch Does Now

The maintained fork no longer assumes new users want to configure engines by hand.

At startup, it now tries to:

- detect bundled KataGo binaries, configs, and bundled weight files
- write a usable default engine configuration automatically
- offer a guided path to download a recommended official weight if needed
- fall back to manual setup only when automatic setup still cannot produce a working configuration

That keeps the common case simple: install, open, fetch, review.

## Screenshot

![LizzieYzy Next-FoxUID Screenshot](screenshot_en.png)

## Release Assets

> [!TIP]
> For most users, the rule is simple: choose `with-katago` if you want the shortest path, and `without.engine` only if you want to manage the engine yourself.

| Platform | Recommended asset | Bundled Java | Bundled KataGo | Install style |
| --- | --- | --- | --- | --- |
| Windows x64 | `windows64.with-katago.installer.exe` | Yes | Yes | Installer with Start Menu and desktop shortcut |
| Windows x64 | `windows64.with-katago.portable.zip` | Yes | Yes | Portable app image, unzip and run `LizzieYzy Next-FoxUID.exe` |
| Windows x64 | `windows64.without.engine.portable.zip` | Yes | No | Portable app with manual engine setup |
| macOS Apple Silicon | `mac-arm64.with-katago.dmg` | App runtime | Yes | Drag to Applications |
| macOS Intel | `mac-amd64.with-katago.dmg` | App runtime | Yes | Drag to Applications |
| Linux x64 | `linux64.with-katago.zip` | Yes | Yes | Unzip and run `start-linux64.sh` |

A few design choices behind this layout:

- Windows now treats the installer as the primary user-facing package instead of `.bat` launchers.
- The Windows no-engine package also moves to a portable `.exe` flow instead of a bat-first flow.
- macOS stays centered on `.dmg` installers, one for Apple Silicon and one for Intel.
- Linux keeps a practical all-in-one package.
- The public release page now stays focused on the 6 primary assets instead of mixing in historical compatibility bundles.

## Compared With The Original Project

| Topic | Original LizzieYzy | Next-FoxUID |
| --- | --- | --- |
| Fox sync | Broken for many users | Restored and maintained |
| Input wording | UID, username, and mixed labels | numeric Fox ID only |
| First launch | Often required manual engine setup | Prefers automatic bundled setup |
| Windows experience | Mostly zip + bat based | Installer-first with portable `.exe` fallback |
| macOS packages | Historically confusing mix | `.dmg` first, split by Apple Silicon / Intel |
| Maintenance | Mostly inactive | Ongoing releases, docs, and support |

## Bundled Engine Details

| Item | Current value |
| --- | --- |
| KataGo version | `v1.16.4` |
| Default bundled weight | `g170e-b20c256x2-s5303129600-d1228401921.bin.gz` |
| First-launch auto setup | Enabled |
| Recommended weight download helper | Included |

Common paths:

- Windows / Linux bundles: `Lizzieyzy/weights/default.bin.gz`
- macOS bundles: `LizzieYzy Next-FoxUID.app/Contents/app/weights/default.bin.gz`
- macOS engine directory: `LizzieYzy Next-FoxUID.app/Contents/app/engines/katago/`

## Docs And Support

| If you need | Go here |
| --- | --- |
| Installation steps | [Installation Guide](docs/INSTALL_EN.md) |
| Package explanations | [Package Overview](docs/PACKAGES_EN.md) |
| Startup, engine, or Fox sync troubleshooting | [Troubleshooting](docs/TROUBLESHOOTING_EN.md) |
| Real-machine verification status | [Tested Platforms](docs/TESTED_PLATFORMS.md) |
| Release process guidance | [Release Checklist](docs/RELEASE_CHECKLIST.md) |
| Help routing | [Support](SUPPORT.md) |
| Change history | [Changelog](CHANGELOG.md) |

## FAQ

<details>
<summary><strong>Why remove username lookup?</strong></summary>

Because it was harder to debug, easier to misunderstand, and less reliable for maintenance. This fork standardizes the user path around numeric Fox ID.
</details>

<details>
<summary><strong>Do I still need to configure the engine by hand on first launch?</strong></summary>

Most users should not. The maintained fork now tries to auto-configure the bundled engine first and only falls back to manual setup when necessary.
</details>

<details>
<summary><strong>Why make the Windows installer the main recommendation?</strong></summary>

Because regular users want a straightforward install flow: download, double-click, finish setup, and open the app. Portable builds still exist, but they are no longer the main path.
</details>

<details>
<summary><strong>Why can macOS still block first launch?</strong></summary>

Current maintenance builds are still unsigned and not notarized. That means Gatekeeper may block the first launch until you choose “Open Anyway” in macOS security settings.
</details>

## Contributing

The most helpful contributions right now are:

- real-machine Windows, Linux, and Intel Mac install reports
- Fox sync compatibility feedback
- better release-page copy, installation docs, and translations
- packaging, first-launch setup, and engine integration fixes

Links:

- [Contributing Guide](CONTRIBUTING.md)
- [Code Of Conduct](CODE_OF_CONDUCT.md)
- [Security Policy](SECURITY.md)
- [Issues](https://github.com/wimi321/lizzieyzy-next-foxuid/issues)
- [Discussions](https://github.com/wimi321/lizzieyzy-next-foxuid/discussions)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- Engine: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- Historical Fox sync references:
  - [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
  - [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)
