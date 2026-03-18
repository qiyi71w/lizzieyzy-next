# Security Policy

## Supported Versions

我们优先修复和跟进最新 release 中仍可复现的安全问题。

| Version | Supported |
| --- | --- |
| Latest release | Yes |
| Older releases | Best effort |
| Original upstream project | No |

## What To Report

如果你发现的问题可能导致以下风险，请按安全问题处理：

- 任意文件覆盖或危险解压行为
- 下载、更新、打包流程中的供应链风险
- 可能泄露用户隐私、系统路径或敏感配置的漏洞
- 明显可被利用的本地提权或远程执行链路

普通功能 bug、兼容性问题、界面文案错误，请直接走公开 issue。

## How To Report

目前仓库的主要协作入口仍是 GitHub。

如果问题包含可直接利用的细节，请不要先把完整利用方法公开发到 issue 里。建议按下面方式处理：

1. 优先使用 GitHub 的私密安全报告入口（如果仓库界面显示可用）。
2. 如果当前没有私密入口，请新建一个标题为 `[Security] Request private contact` 的 issue，只写高层摘要，不要放 PoC、利用代码或敏感数据。
3. 维护者看到后会在 GitHub 上继续跟进可用的私下沟通方式。

## Response Expectations

- 我们会尽量在 7 天内确认收到报告。
- 如果问题可复现，会尽量在 14 天内给出处理状态更新。
- 修复发布后，会在合适范围内公开说明影响和修复版本。

## Packaging Notes

本项目提供带内置 Java 和 KataGo 的发布包。报告安全问题时，请尽量附上：

- 使用的安装包文件名
- 操作系统和版本
- 是否是 `with-katago` 或 `without.engine`
- 复现步骤和影响范围

这些信息会显著提升排查速度。
