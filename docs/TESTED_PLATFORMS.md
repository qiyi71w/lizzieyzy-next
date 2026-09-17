# 已验证平台

这份文档记录当前主发布资产的已知验证状态。

专项变更的触发条件、运行要求和证据字段见[专项验收契约](SPECIALIZED_ACCEPTANCE.md)。本页历史记录只适用于其已记录的提交和环境，新提交需单独判断覆盖范围。

目的不是假装“所有平台都完全测过”，而是明确告诉用户：

- 哪些已经实机验证过
- 哪些已经完成构建与发布验证，但还缺少真实机器反馈
- 哪些仍然主要依赖社区回报

状态说明：

- `Maintainer tested`：维护者在真实机器上完成了安装或启动验证
- `Build verified`：资产命名、内容结构、工作流和公开 release 已验证，但还缺少真实机器反馈
- `Needs report`：目前仍缺少足够反馈

当前主推荐列表覆盖 13 个稳定用户向资产，另有 6 个 Windows 实验便携包；历史兼容包不再放进主推荐区。

## 当前状态

| 包 | 平台 | 当前状态 | 已确认内容 | 备注 |
| --- | --- | --- | --- | --- |
| `windows64.opencl.portable.zip` | Windows x64 | `Build verified` | 默认推荐免安装包已纳入正式发布矩阵 | 当前面向大多数 Windows 用户的首推下载 |
| `windows64.opencl.installer.exe` | Windows x64 | `Build verified` | OpenCL 安装器已纳入正式发布矩阵 | 面向更偏好安装流程的 Windows 用户 |
| `windows64.with-katago.portable.zip` | Windows x64 | `Build verified` | CPU 兜底便携包工作流和公开 release 资产已验证 | OpenCL 不稳定时的兼容兜底 |
| `windows64.with-katago.installer.exe` | Windows x64 | `Build verified` | CPU 兜底安装器工作流和公开 release 资产已验证 | 面向想安装的 CPU 兜底用户 |
| `windows64.nvidia.portable.zip` | Windows x64 + RTX 20/30/40/50 | `Maintainer tested` | RTX 3070 已完成统一 CUDA 12.8/cuDNN 9.8 便携包、B11 首次启动、测速、快速曲线和模型切换实测 | RTX 40/50 仍需社区反馈 |
| `windows64.nvidia.installer.exe` | Windows x64 + RTX 20/30/40/50 | `Build verified` | 统一 NVIDIA CUDA 安装器已纳入发布矩阵 | 安装器和更多显卡型号需要真实反馈 |
| `windows64.without.engine.portable.zip` | Windows x64 | `Build verified` | 无引擎便携包已纳入正式发布矩阵 | 面向进阶用户 |
| `windows64.without.engine.installer.exe` | Windows x64 | `Build verified` | 无引擎安装器已纳入正式发布矩阵 | 面向想安装但自己配引擎的用户 |
| `mac-apple-silicon.with-katago.dmg` | macOS Apple Silicon | `Maintainer tested` | 安装、启动、界面打开、野狐昵称抓谱入口可见 | 当前最完整的实机验证链路 |
| `mac-intel.with-katago.dmg` | macOS Intel | `Build verified` | 已纳入独立发布流程 | 需要真实 Intel Mac 反馈 |
| `linux64.with-katago.zip` | Linux x64 | `Build verified` | 整合包继续提供 | 需要真实 Linux 桌面反馈 |
| `linux64.opencl.zip` | Linux x64 + OpenCL | `Build verified` | OpenCL 包继续提供 | 需要真实 Linux OpenCL 反馈 |
| `linux64.nvidia.zip` | Linux x64 + NVIDIA | `Build verified` | NVIDIA CUDA 包继续提供 | 需要真实 Linux NVIDIA 反馈 |
| `windows64.experimental.directml.portable.zip` | Windows x64 + DirectX 12 GPU | `Needs report` | 官方资产、包结构和启动器纳入 CI | 需要匹配 GPU 真机反馈 |
| `windows64.experimental.openvino.portable.zip` | Windows x64 + Intel | `Needs report` | 官方资产、包结构和启动器纳入 CI | 需要 Intel CPU/iGPU/NPU 真机反馈 |
| `windows64.experimental.rocm.gfx103x.portable.zip` | Windows x64 + AMD RDNA2 | `Needs report` | ROCm 架构包及数据目录纳入 CI | 需要 RDNA2 真机反馈 |
| `windows64.experimental.rocm.gfx110x.portable.zip` | Windows x64 + AMD RDNA3 | `Needs report` | ROCm 架构包及数据目录纳入 CI | 需要 RDNA3 桌面显卡反馈 |
| `windows64.experimental.rocm.gfx1151.portable.zip` | Windows x64 + AMD RDNA3.5 | `Needs report` | ROCm 架构包及数据目录纳入 CI | 需要对应 APU/NPU 真机反馈 |
| `windows64.experimental.rocm.gfx120x.portable.zip` | Windows x64 + AMD RDNA4 | `Needs report` | ROCm 架构包及数据目录纳入 CI | 需要 RDNA4 真机反馈 |

说明：TensorRT 是 RTX 30 系及以下 NVIDIA 显卡的可选方案，普通用户从软件内按需安装；RTX 40/50 默认推荐统一 CUDA 包。GitHub Release 同时保留可选离线分卷并校验数量、清单、大小和 SHA-256。没有对应设备的 DirectML/OpenVINO/ROCm 项目只记录构建验证，不标记实机通过。

### RTX 3070 统一 NVIDIA CUDA 实测（2026-08-24）

- 环境：Windows 11、RTX 3070 Laptop、驱动 `560.76`，走首次轻量真实推理兼容探测路径
- 测试包：临时组装的统一 NVIDIA 便携包，使用官方 KataGo v1.18.1 CUDA 12.8/cuDNN 9.8、完整 NVRTC 运行时和默认 B11 权重
- 全新配置首次启动约 7 秒创建 KataGo 进程，约 10 秒进入 B11 分析；写入兼容标记后再次启动约 3 秒创建进程、约 7 秒进入分析
- 官方 benchmark 完整结束，推荐 `numSearchThreads=5`，约 `209.87 visits/s`；进度按实际阶段持续前进，结束后恢复一个主分析进程
- 310 手棋谱快速胜率曲线约 0.5 秒出现首批结果，约 6 秒完成 31/309，约 20 秒完成 209/309；前台分析保持可用
- 通过真实界面导入并切换官方 B10，再切回 B11；模型切换会使不匹配的 benchmark 结果失效，匹配模型的结果可正确恢复
- 本轮没有 RTX 40/50、DirectML、OpenVINO 或 ROCm 对应硬件，因此这些项目仍只保留构建验证或社区验证状态

### RTX 5090 NVRTC A/B 验证（2026-08-22）

- 环境：Windows、RTX 5090、驱动 `591.86`、Compute Capability `12.0`
- 原始 `next-2026-08-19.4` RTX 50 CUDA 便携包缺少 NVRTC：`katago version` 等待 30 秒无输出，benchmark 等待 100 秒无输出，未创建 GPU context；停止后无残留进程
- 从 NVIDIA CUDA 12.8.0 redistrib manifest 下载并以 SHA-256 `e43603b09f8a52d681ceb814c00b655af19da53692ab91671dabbf8071c8f93d` 验证 `12.8.61` NVRTC compiler 与 builtins，复制到同一包后：`katago version` 约 0.6 秒返回 CUDA `12.8.61`，`nvrtcVersion` 为 `12.8`，benchmark 约 6.2 秒完成，识别 RTX 5090 / CC `12.0`、成功加载 b10 模型、约 `212.77 visits/s`，退出无残留进程
- 这组结果证明缺失 NVRTC 是该包在 RTX 5090 上无法启动的直接原因；新的完整发布资产仍须重新执行同一矩阵，未完成前保持 `Build verified`，不提前标记为完整实机通过

## 我们重点关心什么

如果你帮忙验证，最有价值的是这些信息：

- 包能不能正常下载、安装、解压或挂载
- 首次启动是否被系统安全策略拦截
- 程序能不能进入主界面
- `with-katago` 包里引擎是否正常加载
- “野狐棋谱（输入野狐昵称获取）”是否能抓到公开棋谱

## 如何补充反馈

1. 去 GitHub Issues 里选择 `Installation Report`
2. 写清楚安装包文件名、系统版本、结果和额外步骤
3. 如果有截图或报错，一起附上

相关入口：

- [获取帮助](../SUPPORT.md)
- [发布包说明](PACKAGES.md)
- [安装指南](INSTALL.md)
- [常见问题与排错](TROUBLESHOOTING.md)
