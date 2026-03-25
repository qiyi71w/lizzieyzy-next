# Release Notes Template

下面这份模板可以直接作为 GitHub Release 文案的基础版本。建议每次发版时只替换日期、版本号和资产名，不要临时重写结构。

---

# 中文

**这是一个继续维护 `lizzieyzy` 的版本，重点先把普通用户真正会用到的体验做好：野狐抓谱重新能抓、Windows 安装包更好选、KataGo 更容易直接跑起来。现在下载安装后，直接输入“野狐昵称”就能继续抓最近公开棋谱、分析和复盘。**

![LizzieYzy Next 下载选择图](https://raw.githubusercontent.com/wimi321/lizzieyzy-next/main/assets/package-guide-zh.svg)

## 下载前先看这 4 句

- Windows 用户先下载 `<date>-windows64.with-katago.installer.exe`
- 如果你的电脑有 NVIDIA 显卡，想更快分析，就下载 `<date>-windows64.nvidia.installer.exe`
- 抓野狐棋谱时直接输入“野狐昵称”，程序会自动找到账号并抓最近公开棋谱
- 主推荐整合包已内置 KataGo，第一次启动会优先自动完成配置
- Windows 普通整合包也支持“智能优化”，测速后会自动保存更合适的线程设置
- Windows NVIDIA 整合包会在第一次需要时自动准备官方运行库

## 为什么这一版值得先看

| 你最关心的事 | 这个维护版给你的答案 |
| --- | --- |
| 下载以后能不能直接打开 | Windows 主推荐 `installer.exe`，macOS 主推荐 `.dmg`，Linux 提供整合包 |
| 野狐棋谱还能不能抓 | 已恢复这条链路，并继续维护 |
| 到底该输入什么 | 现在直接输入“野狐昵称”，程序自动找到账号 |
| 第一次打开会不会卡在设置上 | 会优先把分析环境准备好，普通 Windows 包还有智能优化，NVIDIA 包会自动准备运行库 |

## 先下载哪个

| 你的系统 | 直接下载这个 | 说明 |
| --- | --- | --- |
| Windows 64 位 | `<date>-windows64.with-katago.installer.exe` | 主推荐，双击安装，打开后就能开始用 |
| Windows 64 位，NVIDIA 显卡 | `<date>-windows64.nvidia.installer.exe` | 只适合 NVIDIA 显卡电脑，默认分析速度更高 |
| Windows 64 位，NVIDIA 显卡，免安装 | `<date>-windows64.nvidia.portable.zip` | NVIDIA 免安装版 |
| Windows 64 位 | `<date>-windows64.with-katago.portable.zip` | 不想安装时使用，解压后运行 `.exe` |
| Windows 64 位 | `<date>-windows64.without.engine.installer.exe` | 想保留安装流程，但自己决定分析引擎 |
| Windows 64 位 | `<date>-windows64.without.engine.portable.zip` | 想自己决定分析引擎时再选 |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | M1 / M2 / M3 / M4 等机器 |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | Intel 芯片 Mac |
| Linux 64 位 | `<date>-linux64.with-katago.zip` | Linux 64 位整合包 |

## 这次你会直接感受到什么

- 原版已经失效的野狐抓谱链路，重新可用
- 现在直接输入野狐昵称，程序自动找到账号再抓最近公开棋谱
- Windows 主推荐继续放在 `.installer.exe`
- 普通 Windows 包也支持智能优化，一次测速后就会自动保存推荐线程数
- 额外提供 NVIDIA 显卡专用的 CUDA 极速整合包，并会在首启时自动准备官方运行库
- 第一次启动会优先准备好内置分析环境
- 发布页尽量只保留普通用户最容易选的主包

# English

**This maintained release keeps LizzieYzy practical for normal users again: Fox game fetch works again, downloads stay easy to choose, and KataGo is easier to get running well. Download it, open it, enter a Fox nickname, and keep reviewing.**

## Download quick guide

- Windows x64: choose `<date>-windows64.with-katago.installer.exe`
- Windows x64 with NVIDIA GPU: choose `<date>-windows64.nvidia.installer.exe`
- Windows x64 NVIDIA portable: choose `<date>-windows64.nvidia.portable.zip`
- Windows x64 portable: choose `<date>-windows64.with-katago.portable.zip`
- Windows x64 custom engine installer: choose `<date>-windows64.without.engine.installer.exe`
- Windows x64 custom engine: choose `<date>-windows64.without.engine.portable.zip`
- macOS Apple Silicon: choose `<date>-mac-arm64.with-katago.dmg`
- macOS Intel: choose `<date>-mac-amd64.with-katago.dmg`
- Linux x64: choose `<date>-linux64.with-katago.zip`

## Highlights

- Fox game fetching restored
- nickname-based workflow for normal users
- bundled KataGo packages try to auto-finish the first setup
- the regular Windows bundle also supports Smart Optimize for a better saved thread setting
- the Windows NVIDIA bundle can auto-prepare the official runtime on first use
- Windows release stays installer-first
- the public release page stays focused on a smaller, clearer asset set

# 日本語

**この保守版は、元の `lizzieyzy` をまだ使いたい利用者向けに、壊れていた野狐棋譜取得を復旧し、ダウンロード後すぐ使いやすい形に整えたリリースです。**

- Windows x64 は `installer.exe` を優先配布
- NVIDIA GPU 向けには CUDA 版の専用パッケージも提供
- 通常の Windows 同梱版でも Smart Optimize により推奨スレッド数を保存しやすくした
- 初回起動ではそのまま使えるように分析まわりの自動準備を優先
- NVIDIA 同梱版では必要時に公式ランタイムの自動準備も行う
- UI と文書はニックネーム入力を前提に案内

# 한국어

**이 유지보수 릴리스는 아직 `lizzieyzy` 를 쓰고 싶은 사용자를 위해, 고장난 Fox 기보 가져오기를 복구하고 다운로드 후 바로 쓰기 쉬운 형태로 정리한 릴리스입니다.**

- Windows x64 는 `installer.exe` 를 우선 제공
- NVIDIA 그래픽카드용 CUDA 전용 패키지도 함께 제공
- 일반 Windows 통합판도 Smart Optimize 로 더 알맞은 스레드 값을 저장하기 쉽게 했다
- 첫 실행에서는 바로 쓸 수 있도록 분석 환경 자동 준비를 우선 시도
- NVIDIA 통합판은 필요할 때 공식 런타임 자동 준비도 시도한다
- UI 와 문서는 닉네임 입력 기준으로 안내
