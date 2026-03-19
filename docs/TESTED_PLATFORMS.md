# 已验证平台

这份文档记录当前发布包的已知验证状态。

目的不是假装“所有平台都完全测过”，而是把哪些已经实测、哪些只是构建通过、哪些还需要用户反馈写清楚。

如果你在真实机器上安装过某个发布包，不管结果是成功还是失败，都欢迎通过 GitHub 的 `Installation Report` 模板反馈。维护者会把有价值的结果整理到这里。

状态说明：

- `Maintainer tested`：维护者在真实机器上做过安装或启动验证
- `Build verified`：发布包已经构建并检查过内容，但还缺少对应平台的实机反馈
- `Needs report`：目前还缺少足够的反馈，欢迎补充

## 当前状态

| 包 | 平台 | 当前状态 | 已确认内容 | 备注 |
| --- | --- | --- | --- | --- |
| `mac-arm64.with-katago.dmg` | macOS Apple Silicon | `Maintainer tested` | 安装、启动、界面打开、野狐ID抓谱入口可见 | 当前维护阶段最完整的一条实机验证链路 |
| `mac-amd64.with-katago.dmg` | macOS Intel | `Build verified` | 已纳入发布流程并单独产出 Intel Mac 包 | 需要真实 Intel Mac 反馈 |
| `windows64.with-katago.zip` | Windows x64 | `Build verified` | 发布包内容已整理，适合作为主推荐包 | 需要真实 Windows 机器安装反馈 |
| `windows64.without.engine.zip` | Windows x64 | `Build verified` | 无引擎包持续保留，适合自定义引擎 | 需要真实 Windows 机器安装反馈 |
| `windows32.without.engine.zip` | Windows x86 | `Needs report` | 兼容包继续保留 | 需要老机器或兼容环境验证 |
| `linux64.with-katago.zip` | Linux x64 | `Build verified` | 整合包继续提供 | 需要真实 Linux 桌面反馈 |
| `Macosx.amd64.Linux.amd64.without.engine.zip` | Intel Mac / Linux | `Needs report` | 面向进阶用户的无引擎包 | 仅建议熟悉手动配置的用户使用 |

## 我们重点关心什么

如果你帮忙验证，最有价值的是这些信息：

- 包能不能正常解压、挂载或打开
- 首次启动是否被系统安全策略拦截
- 程序能不能进入主界面
- `with-katago` 包里引擎是否正常加载
- “野狐棋谱（输入野狐ID获取）”是否工作

## 如何补充反馈

1. 去 GitHub Issues 里选择 `Installation Report`
2. 写清楚安装包文件名、系统版本、结果和额外步骤
3. 如果有截图或报错，一起附上

相关入口：

- [获取帮助](../SUPPORT.md)
- [发布包说明](PACKAGES.md)
- [安装指南](INSTALL.md)
- [常见问题与排错](TROUBLESHOOTING.md)
