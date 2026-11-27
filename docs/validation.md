# 0.0.1-ac963f2 验证记录

更新日期：2026-11-27（香港时间）。源码 [`ac963f2e59a8af13a66ae950233d6fd102dd423a`](https://github.com/Ktclat/pathpilot/commit/ac963f2e59a8af13a66ae950233d6fd102dd423a) 已推送 `main`。本轮完善主题选项和远程 MCP 快捷添加，本地构建及 CI 已通过。下文记录设备自动测试、人工检查、调度修正及未解决的观测；此前四页导航与模型获取验证见 [ee2b4dd 归档](validation-0.0.1-ee2b4dd.md)。

## 改动

- 「设置 → 主题」统一提供跟随系统/浅色/深色、动态取色和五种固定配色：森林绿、海蓝、紫晶、玫瑰、琥珀。选择固定配色会关闭动态取色，重新开启动态取色时保留原固定色；偏好持久化并进入加密备份。
- 旧配置及旧备份保留原明暗、语言和动态取色偏好，新增固定配色默认森林绿。Android 8–11 使用固定配色，动态选项标明版本要求。
- MCP 添加入口提供 Microsoft Learn、OpenAI Docs、Context7、GitHub 四项预设及自定义连接。前两项直接添加，Context7 填 API Key，GitHub 填 PAT；使用已核查的官方 URL 和 Bearer 配置。
- 预设保存后自动进入详情并获取工具。失败保留连接，可重新获取；同一地址和令牌复用已有连接，不同令牌可分别保存。后续编辑仍使用统一表单，添加不会启动共享。
- 版本为 `0.0.1-ac963f2`，`versionCode` 为 13，常规产物仍仅包含 ARM64。首批测试发现的调度问题通过官方 `junit4.v2` 测试入口处理，见下方失败记录。

## 构建与产物

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.0.1-ac963f2-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.0.1-ac963f2-arm64.apk
```

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 本地 Gradle | 完整检查通过，1 分 56 秒 | [日志](../artifacts/validation/v0.0.1-ac963f2/gradle.log) |
| JVM | 核心 110 项、App 58 项，共 168 项，0 失败、0 错误 | [逐模块结果](../artifacts/validation/v0.0.1-ac963f2/jvm-results.json) |
| Android Lint | 0 错误、42 条警告 | [XML 报告](../artifacts/validation/v0.0.1-ac963f2/lint-results-debug.xml) |
| ARM64 APK | ZIP、包名、签名与 ARM64-only 检查通过 | [产物元数据](../artifacts/validation/v0.0.1-ac963f2/apk.json) |
| GitHub Actions | `ac963f2` 构建成功 | [运行 45939458176](https://github.com/Ktclat/pathpilot/actions/runs/45939458176) · [状态记录](../artifacts/validation/v0.0.1-ac963f2/actions.json) |
| 截图包 | 构建通过，42 秒，已安装 | [构建日志](../artifacts/validation/v0.0.1-ac963f2/capture-build.log) |
| 模拟器 | 最终包 6 次自动执行：4 次通过、2 次失败；4 项不同用例自动通过。大字体人工操作与原图检查通过，自动失败记录保留 | [本轮批次](../artifacts/screenshots/v0.0.1-ac963f2/README.md) |

新增 JVM 检查覆盖旧偏好与旧备份的配色默认值、预设令牌校验、同地址不同账号、已有连接状态保留及 URL 规范化后的复用。

最终产物为 `artifacts/PathPilot-0.0.1-ac963f2-arm64.apk`，包名 `dev.pathpilot.app`，版本 `0.0.1-ac963f2` / `versionCode` 13，大小 21,147,317 字节。

SHA-256：`4a40758c99faabca1b3fba54de3491adfc9f85c7f8b91daa10b78a8f0ccdad9b`。

初始 `aff1629` 的[完整构建](../artifacts/validation/v0.0.1-aff1629/gradle.log)、[截图包构建](../artifacts/validation/v0.0.1-aff1629/capture-build.log)与[APK 元数据](../artifacts/validation/v0.0.1-aff1629/apk.json)单独保留。常规产物为 ARM64 debug 签名 APK；模拟器使用独立的 x86_64 `.capture` 包。

## 设备验证

以下为 `ac963f2` 最终截图包的自动结果，共 6 次执行、4 次通过、2 次失败，4 项不同用例获得通过结果：

| 配置 | 用例 | 自动结果 |
| --- | --- | --- |
| 普通字体 | 主题配色、动态取色、语言与 Activity 重建后的持久化，1 项 | `normal-final` 超时；[同包独立复测通过](../artifacts/screenshots/v0.0.1-ac963f2/theme-isolated/results.json)，204.703 秒 |
| 普通字体 | MCP 免凭据预设自动获取/失败重试，以及凭据预设/账号复用，2 项 | [normal-final 两项通过](../artifacts/screenshots/v0.0.1-ac963f2/normal-final/results.json) |
| 普通字体 | 原 MCP 自定义连接、工具选择与独立共享服务，1 项 | [normal-final 通过](../artifacts/screenshots/v0.0.1-ac963f2/normal-final/results.json) |
| 大字体 | 主题面板、色板、MCP 目录与令牌表单的滚动和操作，1 项 | [large-final 失败](../artifacts/screenshots/v0.0.1-ac963f2/large-final/results.json)，136.934 秒；不计自动通过 |

主题、自定义 MCP 和大字体用例运行生产 MainActivity；预设用例在测试宿主挂载生产 `McpPage`，通过真实 `McpClient` 将 HTTP 目的地重定向到设备回环服务器，保存的连接仍为官方 URL。它们验证界面、存储与请求行为，不代表远程服务的实际账号联调。每批分别保存 runner、APK SHA 和原始截图。

### 失败记录与调度修正

初始 `aff1629/normal-attempt1` 有 3 项失败：主题面板 `ComposeNotIdleException`、无凭据预设 `SnapshotStateObserver` 多线程访问异常、令牌预设 `ArrayIndexOutOfBoundsException`；第四项 MCP 自定义共享未运行，大字体也未运行。[原始结果](../artifacts/screenshots/v0.0.1-aff1629/normal-attempt1/results.json)与 [runner](../artifacts/screenshots/v0.0.1-aff1629/normal-attempt1/instrumentation.log)保留，不计通过。

[logcat](../artifacts/screenshots/v0.0.1-aff1629/normal-attempt1/logcat-after.txt)第 940–1022 行记录网络 IO 恢复经过旧测试 `Unconfined` 路径，在 `DefaultDispatcher-worker` 执行 Recomposer/布局观察。三个相关类改用官方 `junit4.v2` 工厂的 `StandardTestDispatcher` 排队调度，随后 3 个 MCP 用例均通过，未再出现原多线程异常。具体依据见 [调度核查](../artifacts/validation/v0.0.1-ac963f2/test-scheduling.json)。

主题的空闲超时仍未确定根因：切换调度后的 `normal-final` 中主题失败，`large-final` 也在打开主题后检查动态取色时超时，仅留下设置页截图；同一 APK、测试规则和时限下的 `theme-isolated` 则完整通过。各次原始结果均保留。

### 截图与人工检查

`theme-isolated` 的 8 张原图已全部审阅，包含[动态取色](../artifacts/screenshots/v0.0.1-ac963f2/theme-isolated/27-theme-dynamic.png)、[海蓝深色](../artifacts/screenshots/v0.0.1-ac963f2/theme-isolated/28-theme-ocean-dark.png)、[英文动态取色](../artifacts/screenshots/v0.0.1-ac963f2/theme-isolated/30-theme-dynamic-english.png)和[琥珀配色](../artifacts/screenshots/v0.0.1-ac963f2/theme-isolated/31-theme-amber-english.png)。初始 `aff1629` 截图包在测试框架外手动打开主题面板、静置 15 秒的[对照图](../artifacts/screenshots/v0.0.1-aff1629/manual-theme-diagnosis/02-theme-open-steady.png)也正常显示，作为独立诊断证据保留。

`ac963f2` 的大字体人工检查已完成并通过原图审阅：在生产 MainActivity、真实 density 360 / 字体 1.5 下，[主题明暗、动态取色与完成按钮](../artifacts/screenshots/v0.0.1-ac963f2/manual-large/01-theme-controls.png)可达，模式文字正常换行，[五种色板](../artifacts/screenshots/v0.0.1-ac963f2/manual-large/02-theme-palettes-visible.png)可滚动查看；实际切换琥珀和海蓝、开启动态取色后，点击完成回到[正确设置摘要](../artifacts/screenshots/v0.0.1-ac963f2/manual-large/06-settings-after-done.png)。

[MCP 目录底部](../artifacts/screenshots/v0.0.1-ac963f2/manual-large/09-mcp-catalog-bottom.png)可滚动到 GitHub 与自定义入口；[GitHub 表单](../artifacts/screenshots/v0.0.1-ac963f2/manual-large/10-github-token-controls.png)的 URL、令牌输入、获取令牌和添加按钮显示清晰并可达。此人工检查未填写或提交凭据。人工操作通过单独记录，不改写大字体自动用例的超时失败。

恢复 density 320、字体 1.0 后，另一次 MainActivity 冷启动出现 [ANR 弹窗](../artifacts/screenshots/v0.0.1-ac963f2/manual-main-activity/00-startup-anr.png)：[启动等待](../artifacts/screenshots/v0.0.1-ac963f2/manual-main-activity/launch.txt)在 21.308 秒超时，[系统记录](../artifacts/screenshots/v0.0.1-ac963f2/manual-main-activity/startup-lastanr.txt)为触摸分发等待 5,001 毫秒。[主线程转储](../artifacts/screenshots/v0.0.1-ac963f2/manual-main-activity/startup-anr-main-thread.txt)采样时为 Runnable，调用栈位于 Android 双向文本/宽度测量及 Compose LazyList 布局；同期有高 CPU 压力。这项人工观测单独记录，自动执行计数保持 6 次。随后一次 force-stop 后[冷启动成功](../artifacts/screenshots/v0.0.1-ac963f2/manual-main-activity/launch-recovery.txt)，耗时 13.143 秒并恢复共享页显示；根因仍待定位。

上一轮 13 项不同用例、16 次执行以及深色三键导航低对比的观测保留在 [历史归档](validation-0.0.1-ee2b4dd.md)，不计入本轮结果。

恢复后的生产 MainActivity [预设目录](../artifacts/screenshots/v0.0.1-ac963f2/manual-main-activity/03-mcp-preset-catalog.png)与[关于页](../artifacts/screenshots/v0.0.1-ac963f2/manual-main-activity/04-about-version.png)已截图并审查，版本显示 `0.0.1-ac963f2（13）`，与正常 ARM64 产物一致。

验证结束后已恢复设备字号、density 与系统明暗，清理隔离截图包并停止本轮模拟器，见[清理记录](../artifacts/screenshots/v0.0.1-ac963f2/cleanup.json)。

## 远程服务与发布

四项预设字段已按官方资料核查，Context7 的 API Key 要求依据 HTTP 服务默认认证实现。当前云环境的四次无凭据初始化均在代理 CONNECT 阶段返回 403，未到达远程服务；没有在线协议协商、真实 PAT 或远程工具调用成功记录。来源与原始摘要见 [远程 MCP 预设](mcp-presets.md)。

当前提供精选静态预设；在线 Registry 搜索、MCP OAuth 和本地 stdio 运行时尚未实现。本轮尚未发布新的 Release。
