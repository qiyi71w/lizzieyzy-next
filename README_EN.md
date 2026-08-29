<p align="center">
  <img src="assets/hero-english.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next/total?label=Downloads&color=666666" alt="Downloads"></a>
  <a href="https://goagent.top/"><img src="https://img.shields.io/badge/Website-goagent.top-0b6b3a" alt="Official website"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">简体中文</a> · <a href="README_ZH_TW.md">繁體中文</a> · English · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a> · <a href="README_TH.md">ภาษาไทย</a>
</p>

<p align="center">
  <strong>LizzieYzy Next is the maintained lizzieyzy branch for players who use KataGo to review games.</strong><br/>
  It provides Fox nickname fetching, fast full-game analysis, a redesigned winrate graph and bottom quick overview, with releases for Windows, macOS, and Linux.
</p>

<p align="center">
  <a href="https://goagent.top/"><strong>Official Website</strong></a>
  ·
  <a href="https://goagent.top/download/"><strong>Stable Downloads</strong></a>
  ·
  <a href="https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w"><strong>Baidu Download</strong></a>
  ·
  <a href="docs/INSTALL_EN.md"><strong>Installation Guide</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING_EN.md"><strong>Troubleshooting</strong></a>
</p>

> [!NOTE]
> Users in mainland China are encouraged to use the [official download page](https://goagent.top/download/) for stable builds. Installers, Linux packages, and older versions remain available from [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases).
>
> For users in mainland China, a public Baidu Netdisk download is available:
> [https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w](https://pan.baidu.com/s/1wthaL8YwGMxy_u0U7Mabpw?pwd=3i8w)
> Extraction code: `3i8w`

> [!TIP]
> [Chinese QQ group: 299419120](https://qm.qq.com/q/JZoeojjteg)
>
> It is the fastest place for day-to-day user feedback, bug reports, and feature discussion.

## What you can do right away

| What you want | How the project handles it now |
| --- | --- |
| Fetch recent public Fox games | Enter a Fox nickname and let the app resolve the account automatically |
| See the whole game faster | Use fast full-game analysis instead of relying only on move-by-move clicking |
| Find problem moves faster | Use the redesigned main winrate graph and the bottom heat overview strip |
| Avoid setup work | Use bundled KataGo, bundled weight, and first-launch auto setup |
| Avoid installation | Use the portable Windows packages |
| Use board sync | Windows release packages include the native `readboard.exe` |

## What to download first

Users in mainland China are encouraged to choose common stable builds from the [official download page](https://goagent.top/download/). Installers, Linux packages, and older versions are available from [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases).

<p align="center">
  <img src="assets/package-guide.svg" alt="LizzieYzy Next package guide" width="100%" />
</p>

| Your situation | Find the file that contains this keyword on Releases |
| --- | --- |
| Most Windows users, recommended, no installer | `*windows64.opencl.portable.zip` |
| Windows, OpenCL build, installer option | `*windows64.opencl.installer.exe` |
| Windows, OpenCL is unstable, CPU fallback, no installer | `*windows64.with-katago.portable.zip` |
| Windows, CPU fallback, installer option | `*windows64.with-katago.installer.exe` |
| Windows, RTX 20/30/40/50 NVIDIA GPU, no installer | `*windows64.nvidia.portable.zip` |
| Windows, RTX 20/30/40/50 NVIDIA GPU, installer option | `*windows64.nvidia.installer.exe` |
| Windows, RTX 30 series and earlier, optional TensorRT | Start with the unified NVIDIA package, then install TensorRT from `KataGo Auto Setup` |
| Windows, DirectX 12 GPU, DirectML testing | `*windows64.experimental.directml.portable.zip` |
| Windows, Intel GPU/NPU, OpenVINO testing | `*windows64.experimental.openvino.portable.zip` |
| Windows, supported AMD GPU, ROCm testing | Pick the matching `*windows64.experimental.rocm.*.portable.zip` |
| Windows, bring your own engine, no installer | `*windows64.without.engine.portable.zip` |
| Windows, bring your own engine, installer option | `*windows64.without.engine.installer.exe` |
| macOS Apple Silicon, then drag the app to Applications | `*mac-apple-silicon.with-katago.dmg` |
| macOS Intel, then drag the app to Applications | `*mac-intel.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

Full packages use KataGo `v1.18.1` for CPU, OpenCL, CUDA, TensorRT, Metal, and Linux backends. Linux NVIDIA remains on CUDA 12.1 for runtime compatibility.

The recommended full package includes the official flagship B11 model `b11c768h12nbt3tflrs-fson-silu.bin.gz` (about 202 MiB). An RTX 3070 comparison measured about 40% lower search throughput than B10, which remains available from `KataGo Auto Setup -> Weights`.

NVIDIA and TensorRT notes:

- `KataGo Auto Setup` detects the NVIDIA GPU and Compute Capability, then recommends whether TensorRT is a good fit. Manual installation remains available if detection fails.
- RTX 40/50 use CUDA by default. TensorRT is optional for RTX 30 series and earlier.
- NVIDIA driver `570.65` or newer loads directly. Versions `528.33–570.64` run one lightweight inference probe on first launch; older drivers show a repair state.
- GTX 10 series and older cards should use OpenCL. If OpenCL is unstable, switch to `*windows64.with-katago.portable.zip`.

## Start in 3 steps

1. Download the right stable package from [Stable Downloads](https://goagent.top/download/); use GitHub Releases for installers, Linux, or older versions.
2. Open `Fox Kifu` and enter a Fox nickname.
3. Fetch the games, run fast full-game analysis, and use the graph plus overview to jump to important moves.

<p align="center">
  <a href="assets/fox-id-demo.gif">
    <img src="assets/fox-id-demo-cover.png" alt="LizzieYzy Next Fox nickname demo" width="100%" />
  </a>
</p>

<p align="center">
  If GitHub delays GIF playback, click the image above to open the full animation.
</p>

## Interface preview

<p align="center">
  <img src="assets/interface-overview-2026-04.png" alt="LizzieYzy Next interface preview" width="100%" />
</p>

The main graph and quick overview show:

<p align="center">
  <img src="assets/winrate-quick-overview-2026-04.png" alt="LizzieYzy Next winrate graph and quick overview" width="46%" />
</p>

- blue / magenta lines: the changing winrate picture
- green line: score lead changes
- bottom heat strip: where the whole game has the biggest mistakes
- vertical guide line: the current move or hovered move position

## How it differs from the original LizzieYzy

| Comparison | Original `lizzieyzy` | `LizzieYzy Next` |
| --- | --- | --- |
| Current status | Historical project remembered by many users, but without practical ongoing maintenance | Actively maintained branch focused on usability and releases |
| Fox fetching | Older flow broke for many users | Common fetching flow restored, now with nickname input |
| Input model | More dependent on knowing the account number first | Enter the Fox nickname and let the app resolve the account |
| KataGo setup barrier | Often means fixing your own environment | Recommended bundles already include KataGo and a default weight |
| Windows download experience | More guesswork for users | Clear portable-first recommendation |
| Board sync path | More manual assembly for users | Windows release packages include the native `readboard.exe` |

## First launch on macOS

Choose the package for your Mac, open the DMG, drag `LizzieYzy Next` to Applications, eject the installer disk, and launch it from Finder's Applications folder. Official releases are signed and notarized; if macOS still blocks the app, follow the [Installation Guide](docs/INSTALL_EN.md).

## Documentation and contribution

- [Support Guide](SUPPORT.md)
- [Installation Guide](docs/INSTALL_EN.md)
- [Package Overview](docs/PACKAGES_EN.md)
- [Troubleshooting](docs/TROUBLESHOOTING_EN.md)
- [Tested Platforms](docs/TESTED_PLATFORMS.md)
- [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)
- [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions)
- [Chinese QQ group: 299419120](https://qm.qq.com/q/JZoeojjteg)
- [Roadmap](ROADMAP.md)
- [Contributing](CONTRIBUTING.md)
- [Changelog](CHANGELOG.md)

## Credits

- Original project: [yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo: [lightvector/KataGo](https://github.com/lightvector/KataGo)
- Board sync: [qiyi71w/readboard](https://github.com/qiyi71w/readboard)

Thanks to [qiyi71w](https://github.com/qiyi71w) for maintaining and improving readboard.

Thanks to everyone who has contributed:

<p align="left">
  <a href="https://github.com/wimi321/lizzieyzy-next/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=wimi321/lizzieyzy-next" alt="LizzieYzy Next contributors" />
  </a>
</p>

Historical Fox fetching references:

- [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
- [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)

## Translations

Translations are welcome; please submit a Pull Request.
