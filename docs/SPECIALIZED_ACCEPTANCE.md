# 专项验收契约

常规 CI、Windows 原生桌面、真实 GPU、完整打包分别给出结论。一次运行只证明其实际执行的场景、提交和环境；本页定义如何选择验收及记录证据，不是所有平台已经通过的声明。

## 按改动风险选择验收

| 层级 | 触发条件 | 运行条件与入口 | 通过含义 |
| --- | --- | --- | --- |
| 确定性 UI / 状态回归 | 分组、窄宽度重排、长文案、修复/启用资格与副作用变更 | JDK 21、Maven、可写的隔离工作目录；下述现有 JUnit 随两平台 Java gate 执行 | 被执行的布局与状态契约通过；fixture 仅证明受控输入下的行为 |
| Windows 原生桌面 | 窗口尺寸、主题、字体、DPI、滚动、焦点、按钮可达性等可见行为变更 | Windows 原生 JVM、交互桌面、实际产品主题、100% / 150% / 200% 显示缩放；启动指定 SHA 的 shaded JAR | 指定主题、缩放、窗口尺寸下实际观察的场景通过 |
| 真实 GPU | GPU 检测、安装、修复、显式启用、后端切换变更 | 对应 GPU/驱动、真实 KataGo/backend、权重与配置、必要的下载网络/代理、隔离可修复目录 | 实际组件操作、前台引擎身份与启动分析结果满足场景；纯布局变更不自动要求整套 GPU 流程 |
| 完整打包 / 发布 | 打包脚本、原生资源、运行时、启动器、安装/升级、签名、资产身份或上传变更 | 目标 OS/架构及平台 workflow 所需工具、真实资源、网络；签名/发布需要对应凭据和授权 | 选定真实资产的组装、审计、平台 smoke，以及实际执行的签名/发布步骤通过 |

先列触发的层级和场景，再检查工具、平台、服务与权限。缺少前提时记录具体原因并完成独立可执行项。凭据只记录“可用/不可用/未检查”，不记录值。普通 PR 不新增 GPU runner；已有 headless 测试留在常规 CI，不另跑同一清单制造独立 UI 绿勾。

## 确定性覆盖与执行边界

下表按可观察契约引用已有测试；方法名用于定位，不是额外的 CI 测试清单。

| 目标契约 | 已有证据 | 边界 |
| --- | --- | --- |
| 分组内按钮可见、提示与动作不重叠 | [KataGoAccelerationLayoutTest](../src/test/java/featurecat/lizzie/gui/KataGoAccelerationLayoutTest.java)：`maintenanceActionsWrapWithoutLeavingTheirGroup`、`hintAndActionsDoNotOverlapInNarrowBlock`、`experimentalSelectorAndButtonAreStacked` | 使用生产布局在 EDT 排列轻量 Swing 组件，检查边界和相对位置；不证明完整原生对话框的主题绘制 |
| 窄宽度重排及再次放大/缩小 | 同类：`narrowStatusRowUsesTheFullWidthInsteadOfA24PixelValueColumn`、`statusRowReturnsToColumnsAfterGrowing`、`growingAndShrinkingReflowsBothDirections`、`viewportWidthIsRespected` | 检查实际组件尺寸与重排，不是截图或 DPI 验收 |
| 文字完整、最后一行可见 | 同类：`allEightResourceBundlesKeepNarrowStatusAndHintsVisible`、`wrappedHeightIncludesSwingsCaretMarginAtLineBreakBoundaries`；[KataGoAutoSetupDialogLayoutTest](../src/test/java/featurecat/lizzie/gui/KataGoAutoSetupDialogLayoutTest.java)：`localizedButtonWidthIncludesTheEntireThaiLabel`、`longLocalizedWeightActionsWrapWithoutClipping`、`wrappedStatusCanShrinkAgainAfterBackendSwitch` | 文本布局测试含 `modelToView2D` 的末行边界；放大字体是受控输入，不是 Windows 150%/200% 原生证据 |
| 窗口工作区适配、权重动作重排 | `KataGoAutoSetupDialogLayoutTest`：`dialogShrinksBelowItsDesktopMinimumAtHighDisplayScaling`、`dialogPlacementStaysInsidePositiveAndNegativeMonitorCoordinates`、`weightActionsStayInlineAtTheDefaultDialogWidth` | 几何计算与生产行布局；真实显示器工作区、系统缩放及窗口装饰另验 |
| 修复与启用资格分离 | [TensorRtAccelerationViewTest](../src/test/java/featurecat/lizzie/gui/TensorRtAccelerationViewTest.java)：`readyComponentsWithInactiveProfileStayDistinguishable`、`incompleteWeightAndGtpDoNotBlockRepairAndAreListedForEnable`、`missingActivationItemsAreExposedOnTheAccessibleEnableDescription` | 验证组件就绪、profile 未启用和缺失项目的可观察状态；不以字符串键断言代替动作执行 |
| 修复不改 profile，只有显式启用写入 | [TensorRtComponentRepairTest](../src/test/java/featurecat/lizzie/util/TensorRtComponentRepairTest.java)：`missingRuntimeEngineAndCompanionRepairToReadyWithoutChangingProfiles`、`onlyEnableTensorRtWritesTheProfileAndMissingItemsBlockActivation` | 真实修复/启用方法使用临时配置与受控资源 fixture，检查文件和 profile 前后状态；模拟 Windows/GPU 输入，不启动真实 DirectML/TensorRT 引擎 |

上述四类均可在 headless 下运行。两平台 [ci.yml](../.github/workflows/ci.yml) 的 `java-linux` / `java-windows` 通过 [run_local_ci.py](../scripts/run_local_ci.py) 执行完整 Maven `verify`，均显式指定 `-Djava.awt.headless=true`、`-DskipTests=false`，没有为这些类另设排除。它们使用 Surefire 默认识别的 `*Test` 命名，留在 [pom.xml](../pom.xml) 的常规测试生命周期；shaded JAR、`LoggingProviderSmokeIT` 和 JaCoCo 仍由原 Java gate 负责。

需要显示器的反例：[WholeGameAnalysisDialogLayoutTest](../src/test/java/featurecat/lizzie/gui/WholeGameAnalysisDialogLayoutTest.java) 的窗口/缩放/输入动作测试先调用 `assumeFalse(GraphicsEnvironment.isHeadless())`。因此两平台全量 headless gate 不执行这些窗口断言。其他带 assumptions 的测试也以该次 JUnit XML 中的实际 skip 为准；不得固定全仓库 skips 数量，或把 skipped 算作通过。

### 聚焦复核

在仓库根目录运行（WSL 先 `unset DISPLAY`；PowerShell 不执行这一行）：

```bash
mvn -B -Dfmt.skip=true -Djava.awt.headless=true -Dtest=KataGoAccelerationLayoutTest,KataGoAutoSetupDialogLayoutTest,TensorRtAccelerationViewTest,TensorRtComponentRepairTest,WholeGameAnalysisDialogLayoutTest test
```

若需要隔离应用工作目录，增加 `-Dlizzie.work.dir=<独立目录>`。同一 checkout 不并行运行写入 `target` 的 Maven 调用。完整平台入口和分组使用方式见 [开发指南](DEVELOPMENT.md)，不要用上述聚焦命令替换完整 Java gate。

记录：目标 SHA、源码是否有未提交修改、OS/JDK/Maven、headless 值、命令、开始/结束时间、退出码、逐类 tests/failures/errors/skipped、实际跳过的测试及 assumption、Surefire XML/日志路径；hosted 运行另附 run URL、event、head SHA、job 与 artifact。dry-run 只记录命令规划成功。

覆盖盘点只有两种完成结果：目标确定性契约已有覆盖且无确认缺口；或所有确认缺口已补成行为回归、进入两平台 Java gate 并取得测试/hosted 证据。若缺口需要修改业务实现，记录可达行为与阻塞，先确认业务修复范围，不能仅列候选便关闭补缺任务。

## Windows 原生桌面

先固定完整 SHA，在独立 checkout 中按[开发指南的本地构建步骤](DEVELOPMENT.md#本地构建)生成候选，保存源码 SHA、构建命令、工具版本与构建日志。记录 shaded JAR 路径、启动时间、PID、JVM 命令、窗口标题和隔离配置目录，使截图能对应到实际构建与进程。维护机器的 candidate 工具可用时，保留其 `candidate.json`、`run.json`；其他机器记录同等证据即可。启动 shaded JAR；普通 JAR 没有 `Main-Class`。

每个实际产品主题分别列 100% / 150% / 200% 档位，记录主题名称、浅/深模式和版本。按改动风险选择默认宽度、窄宽度、放大再缩小、长文案、分组及滚动场景；同一场景逐档观察：

1. 打开受影响页面，确认 TensorRT 主动作、维护动作、实验后端组可见且彼此分开。
2. 缩窄再放大，检查动作重排、状态/提示不重叠、末行及按钮文字完整；必要时滚动到各组并记录位置。
3. 用鼠标与键盘触发受影响动作，观察禁用态、焦点和动作语义。涉及真实安装/切换时同时执行下一节 GPU 场景。

桌面记录每行必须填写以下字段：

| 场景 | 构建 SHA / artifact / run.json | Windows / JVM / 主题 / 语言 | 缩放与 DPI | 窗口尺寸 | 用户动作 | 预期 | 实际 | 截图 | 状态 / 原因 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 一个场景及一个缩放档位 | 完整 SHA、shaded JAR 路径及进程身份 | 具体版本与实际主题 | 100%/150%/200% 及实际 DPI | 外框和内容区宽×高，注明逻辑/物理像素 | 按顺序记录 | 可判定的结果 | 实际观察，不复制预期 | 可访问的图片路径或链接 | PASS / FAIL / BLOCKED / NOT RUN |

未执行档位逐项写 `NOT RUN`；缺少桌面/设备写 `BLOCKED`；决定放弃某档位仍写 `NOT RUN` 并附决定者、日期与原因，不计 PASS。WSLg、Xvfb、headless、手工放大 Swing 字体均不替代 Windows 原生实际主题与缩放证据。

## 真实 GPU

运行前记录：应用完整 SHA/候选身份、Windows 版本、GPU 型号及设备、驱动版本、KataGo 与 backend/runtime 版本、模型/配置、初始前台引擎、网络/代理可用状态和隔离目录。硬件不匹配则对应场景保持 `BLOCKED`；不操作日常用户的引擎文件或凭据。

对于 TensorRT 修复/启用边界，至少保留以下完整序列：

1. 在真实 DirectML 前台开始分析，保存实际命令、进程/引擎身份和一段分析输出作为基线。
2. 在隔离目录制造场景所需的 TensorRT 缺失组件，记录缺失项；从应用点击修复 TensorRT，保存开始、阶段、结束或错误日志。
3. 修复完成后确认组件状态正确，前台仍是原 DirectML 引擎，profile 未被暗中切换，并再次取得 DirectML 分析输出。修复成功不是启用成功。
4. 用户显式点击启用 TensorRT；记录此次动作和实际切换结果，保存真实 TensorRT 启动日志（可辨认 backend/GPU/模型）以及对应分析结果。只有写入 profile 或出现成功提示不能证明启用完成。
5. 其他受影响检测、安装、取消/失败、后端切换场景按改动风险单列；保留失败现场后再恢复隔离环境。

GPU 记录每行包含：场景 ID、SHA/构建身份、硬件/驱动/KataGo/backend/runtime、初始状态、带时间的动作序列、预期、实际、前后引擎身份、真实启动与分析日志路径及时间段、截图、PASS/FAIL/BLOCKED/NOT RUN 和原因。日志发布前脱敏，保留判断引擎身份与分析结果所需信息。PowerShell parser、资源下载 fixture、GPU 检测 fixture 均只证明其受控范围，不证明硬件链路。

## 完整打包与发布

真实入口与参数以目标提交的 workflow 为准：

| 平台 | Workflow | 组装与后续检查 |
| --- | --- | --- |
| Windows | [build-windows-release.yml](../.github/workflows/build-windows-release.yml) | [package_windows_exe.sh](../scripts/package_windows_exe.sh)、[validate_release_assets.sh](../scripts/validate_release_assets.sh)；目标安装/启动风险另用 [windows_smoke_test.ps1](../scripts/windows_smoke_test.ps1)、[windows_upgrade_smoke.ps1](../scripts/windows_upgrade_smoke.ps1) |
| Linux | [build-linux-release.yml](../.github/workflows/build-linux-release.yml) | [package_release.sh](../scripts/package_release.sh)、资产内容审计与目标桌面启动 |
| macOS | [arm64](../.github/workflows/build-macos-arm64-release.yml) / [amd64](../.github/workflows/build-macos-amd64-release.yml) | [package_macos_dmg.sh](../scripts/package_macos_dmg.sh)、[sign_macos_release_with_retry.sh](../scripts/sign_macos_release_with_retry.sh)、资产审计和原生安装/启动 |

先检查目标平台工具和 workflow 声明的下载/签名/发布条件。平台 smoke 使用可丢弃的配置；执行前检查其配置清理选项。签名是否执行、为何跳过、验证结果按 [macOS 签名说明](MACOS_SIGNING.md)单独记录，不用打包成功推断签名成功。

记录：源码 SHA、tag/版本/渠道、OS/架构、workflow run URL/event/job、构建工具、真实资源来源及版本、资产文件名及身份校验、内容审计日志、安装/portable/DMG 启动与升级场景、签名/公证状态、上传目标与结果、预期/实际、证据路径、逐项状态。组装、签名、上传和实机启动分别给结论，未执行步骤明确标注。

脚本 fixture 单测通过不代表真实安装包、资源闭包、签名、发布身份或上传通过。源码 shaded JAR 验收也不证明 portable 启动器/安装器/升级路径。需要不发布的 packaging smoke 时先明确资产、平台与不写 Release 的范围；不能为文档或常规 CI 验收暗中触发具有发布写权限的 workflow。

## 历史证据与结论

[已验证平台](TESTED_PLATFORMS.md)记录历史环境；#453 等既有验收只适用于原记录的提交、构建和环境。缺少完整 SHA 或环境信息的旧记录保留其原始事实并注明不足，不补猜身份。新提交不自动继承历史 PASS；复用时必须指出原证据身份、覆盖边界和为何仍适用，未覆盖的新风险另验。

每次结论分别列：确定性测试、原生桌面、GPU、打包/发布的实际状态与证据。审查通过与验收完成是两道门；仅勾选有证据的验收项。专项契约/覆盖盘点完成，不等于全部硬件和发布资产已经验收。
