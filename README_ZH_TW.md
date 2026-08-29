<p align="center">
  <img src="assets/hero-chinese.svg" alt="LizzieYzy Next" width="100%" />
</p>

<p align="center">
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/v/release/wimi321/lizzieyzy-next?display_name=tag&label=Release&color=111111" alt="Release"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/stargazers"><img src="https://img.shields.io/github/stars/wimi321/lizzieyzy-next?style=flat&color=444444" alt="Stars"></a>
  <a href="https://github.com/wimi321/lizzieyzy-next/releases"><img src="https://img.shields.io/github/downloads/wimi321/lizzieyzy-next/total?label=Downloads&color=666666" alt="Downloads"></a>
  <a href="https://goagent.top/"><img src="https://img.shields.io/badge/Website-goagent.top-0b6b3a" alt="官方網站"></a>
  <img src="https://img.shields.io/badge/Platforms-Windows%20%7C%20macOS%20%7C%20Linux-888888" alt="Platforms">
</p>

<p align="center">
  <a href="README.md">简体中文</a> · 繁體中文 · <a href="README_EN.md">English</a> · <a href="README_JA.md">日本語</a> · <a href="README_KO.md">한국어</a> · <a href="README_TH.md">ภาษาไทย</a>
</p>

<p align="center">
  <strong>LizzieYzy Next 是仍在維護的 lizzieyzy 分支，面向使用 KataGo 覆盤的一般棋友。</strong><br/>
  提供野狐暱稱抓譜、快速全盤分析、新版勝率圖和底部快速概覽，並發佈 Windows、macOS、Linux 版本。
</p>

<p align="center">
  <a href="https://goagent.top/"><strong>官方網站</strong></a>
  ·
  <a href="https://goagent.top/download/"><strong>正式版下載</strong></a>
  ·
  <a href="docs/INSTALL.md"><strong>安裝說明</strong></a>
  ·
  <a href="docs/TROUBLESHOOTING.md"><strong>常見問題</strong></a>
</p>

> [!TIP]
> [專案討論 QQ 群：299419120](https://qm.qq.com/q/JZoeojjteg)
>
> 歡迎交流使用問題、回報 bug、分享使用體驗，或者討論接下來最想加的功能。

> [!NOTE]
> 中國大陸使用者建議從 [官方下載頁面](https://goagent.top/download/) 下載正式版；需要安裝程式、Linux 套件或歷史版本時，可使用 [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)。

## 你打開後馬上能做什麼

| 你想做什麼 | 這個專案現在怎麼解決 |
| --- | --- |
| 抓最近公開野狐棋譜 | 直接輸入野狐暱稱，程式自動匹配帳號並抓譜 |
| 快速看整盤走勢 | 提供快速全盤分析，不用完全靠一步一步手點 |
| 快速找問題手 | 提供新版主勝率圖和底部熱力概覽，更容易一眼看出大問題手 |
| 少折騰設定 | 推薦整合包已內建 KataGo、預設權重和首次自動設定 |
| 不想安裝 | Windows 預設優先推薦 `portable.zip` 免安裝包 |
| 做棋盤同步 | Windows 主發佈包已內建原生 `readboard.exe`，同步入口更清楚 |

## 先下載哪個

中國大陸使用者建議從 [官方下載頁面](https://goagent.top/download/) 選擇常用正式版；安裝程式、Linux 套件和歷史版本可在 [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases) 下載。

| 你的情況 | 到 Releases 裡找包含這個關鍵字的檔案 |
| --- | --- |
| Windows 大多數使用者，推薦，免安裝 | `*windows64.opencl.portable.zip` |
| Windows，OpenCL 版，想安裝 | `*windows64.opencl.installer.exe` |
| Windows，OpenCL 不穩定，CPU 相容兜底，免安裝 | `*windows64.with-katago.portable.zip` |
| Windows，NVIDIA 顯示卡，想更快，免安裝 | `*windows64.nvidia.portable.zip` |
| Windows，自己設定引擎，免安裝 | `*windows64.without.engine.portable.zip` |
| macOS Apple Silicon | `*mac-apple-silicon.with-katago.dmg` |
| macOS Intel | `*mac-intel.with-katago.dmg` |
| Linux | `*linux64.with-katago.zip` |

## 三步開始

1. 到 [正式版下載頁](https://goagent.top/download/) 下載適合自己系統的包；需要安裝程式、Linux 或歷史版本時使用 GitHub Releases。
2. 打開程式後，點擊 `野狐棋譜`，輸入野狐暱稱。
3. 抓到棋譜後繼續做快速全盤分析，用主勝率圖和底部快速概覽直接定位關鍵手。

## 文件與參與

- [取得協助](SUPPORT.md)
- [安裝說明](docs/INSTALL.md)
- [發佈包說明](docs/PACKAGES.md)
- [常見問題與排錯](docs/TROUBLESHOOTING.md)
- [已驗證平台](docs/TESTED_PLATFORMS.md)
- [GitHub Releases](https://github.com/wimi321/lizzieyzy-next/releases)
- [GitHub Discussions](https://github.com/wimi321/lizzieyzy-next/discussions)
- [QQ 群：299419120](https://qm.qq.com/q/JZoeojjteg)
- [專案路線圖](ROADMAP.md)
- [參與貢獻](CONTRIBUTING.md)
- [更新日誌](CHANGELOG.md)

## 致謝

- 原專案：[yzyray/lizzieyzy](https://github.com/yzyray/lizzieyzy)
- KataGo：[lightvector/KataGo](https://github.com/lightvector/KataGo)
- 棋盤同步工具：[qiyi71w/readboard](https://github.com/qiyi71w/readboard)

感謝 [qiyi71w](https://github.com/qiyi71w) 持續維護和改進 readboard。

感謝所有參與提交的貢獻者：

<p align="left">
  <a href="https://github.com/wimi321/lizzieyzy-next/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=wimi321/lizzieyzy-next" alt="LizzieYzy Next 貢獻者" />
  </a>
</p>

野狐抓譜參考：

- [yzyray/FoxRequest](https://github.com/yzyray/FoxRequest)
- [FuckUbuntu/Lizzieyzy-Helper](https://github.com/FuckUbuntu/Lizzieyzy-Helper)

## 參與翻譯

歡迎提交 README 翻譯 PR。Translations are welcome; please submit a Pull Request.
