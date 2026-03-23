# Release Notes Template

下面这份模板可以直接作为 GitHub Release 文案的基础版本。建议每次发版时只替换日期、版本号和资产名，不要临时重写结构。

---

# 中文

**原版 `lizzieyzy` 的野狐棋谱同步很多人已经用不了了。这个维护版先把最常用的这条链路重新修好：下载安装后能直接打开，输入“野狐昵称”就能继续抓谱、分析、复盘，程序会自动找到账号并获取最近公开棋谱。**

![LizzieYzy Next-FoxUID 下载选择图](https://raw.githubusercontent.com/wimi321/lizzieyzy-next-foxuid/main/assets/package-guide-zh.svg)

## 下载前先看这 3 句

- Windows 用户先下载 `<date>-windows64.with-katago.installer.exe`
- 抓野狐棋谱时直接输入“野狐昵称”，程序会自动找到账号并抓最近公开棋谱
- 第一次启动会优先把分析环境准备好，大多数人不用先手动设置

## 第一次来先这样理解

| 你最关心的事 | 这个维护版给你的答案 |
| --- | --- |
| 下载以后能不能直接打开 | Windows 主推荐 `installer.exe`，macOS 主推荐 `.dmg`，Linux 给整合包 |
| 野狐棋谱还能不能抓 | 已恢复这条链路，并继续维护 |
| 到底该输入什么 | 现在直接输入“野狐昵称”，程序自动找到账号 |
| 第一次打开会不会卡在设置上 | 会优先把分析环境准备好，大多数人不用先手动折腾 |

## 先下载哪个

| 你的系统 | 直接下载这个 | 说明 |
| --- | --- | --- |
| Windows 64 位 | `<date>-windows64.with-katago.installer.exe` | 主推荐，双击安装，打开后就能开始用 |
| Windows 64 位 | `<date>-windows64.with-katago.portable.zip` | 不想安装时使用，解压后运行 `.exe` |
| Windows 64 位 | `<date>-windows64.without.engine.portable.zip` | 想自己决定分析引擎时再选 |
| macOS Apple Silicon | `<date>-mac-arm64.with-katago.dmg` | M1 / M2 / M3 / M4 等机器 |
| macOS Intel | `<date>-mac-amd64.with-katago.dmg` | Intel 芯片 Mac |
| Linux 64 位 | `<date>-linux64.with-katago.zip` | Linux 64 位整合包 |

## 这次你会直接感受到什么

- 原版失效的野狐抓谱链路已经修好，并继续维护
- 现在直接输入野狐昵称，程序自动找到账号再抓最近公开棋谱
- Windows 主推荐直接改成 `.installer.exe`
- 第一次启动会优先准备好内置分析环境
- 发布页只保留 6 个主包，普通用户不用在历史资产里做选择

# English

**This maintained release restores the broken Fox kifu sync path. Download it, open it, enter a Fox nickname, and keep reviewing. The app resolves the matching account automatically and fetches recent public games for you.**

## Download quick guide

- Windows x64: choose `<date>-windows64.with-katago.installer.exe`
- Windows x64 portable: choose `<date>-windows64.with-katago.portable.zip`
- Windows x64 custom engine: choose `<date>-windows64.without.engine.portable.zip`
- macOS Apple Silicon: choose `<date>-mac-arm64.with-katago.dmg`
- macOS Intel: choose `<date>-mac-amd64.with-katago.dmg`
- Linux x64: choose `<date>-linux64.with-katago.zip`

## Highlights

- Fox sync restored
- nickname-based workflow for normal users
- First launch prepares the bundled analysis environment for most users
- Windows release is now installer-first
- The public release page is now centered on a smaller, clearer asset set

# 日本語

**このメンテナンス版では、壊れていた野狐棋譜同期を復旧し、ダウンロード後すぐに野狐のニックネームで公開棋譜を取得して解析を続けられるようにしました。アプリが対応するアカウントを自動で見つけます。**

- Windows x64 は `installer.exe` を優先配布
- 初回起動ではそのまま使えるように分析まわりの自動準備を優先
- UI と文書はニックネーム入力を前提に案内

# 한국어

**이 유지보수 릴리스는 고장난 Fox 기보 동기화를 복구해, 내려받은 뒤 바로 Fox 닉네임으로 공개 기보를 가져오고 분석을 이어갈 수 있게 합니다. 앱이 맞는 계정을 자동으로 찾아 줍니다.**

- Windows x64 는 `installer.exe` 를 우선 제공
- 첫 실행에서는 바로 쓸 수 있도록 분석 환경 자동 준비를 우선 시도
- UI 와 문서는 닉네임 입력 기준으로 안내
