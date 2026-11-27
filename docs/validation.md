# 0.0.2+d58b2f4 验证记录

交付源码 [`d58b2f448152c8b0c499709fad974577446cc519`](https://github.com/Ktclat/pathpilot/commit/d58b2f448152c8b0c499709fad974577446cc519) 已推送 `main`，APK 版本为 `0.0.2+d58b2f4`，Android `versionCode` 为 14。本地完整检查与常规 ARM64 CI 已通过；随后 KVM 模拟器 7 项用例全部通过。本地模拟器的失败与未完成记录单独保留，具体范围见下文。

上一轮 `0.0.1-ac963f2` 的完整结果已[原样归档](validation-0.0.1-ac963f2.md)，其中的测试计数、APK 和 CI 均不作为本轮结果。

## 本轮改动

| 范围 | 当前实现 |
| --- | --- |
| Codex 浏览器登录 | Authorization Code + PKCE；本机回调使用 `127.0.0.1:1455`，端口占用时尝试 `1457`；保留设备代码登录 |
| 授权状态 | 设备代码轮询遇到可重试的临时网络错误时继续等待；取消会停止网络请求并关闭回调监听 |
| 错误与保存 | 授权错误使用本地化文案；令牌保存失败后单独重试本地保存，不重新发起授权 |
| MCP 调用记录 | SQLite 保存最近 1,000 条调用元数据；支持今日、近 7 天、近 30 天、全部，以及连接和工具名称筛选；提供存储失败重试和清空操作 |
| 模拟器 CI | 可手动运行 `Codex and MCP emulator tests`，执行 Codex 登录、SQLite 与 MCP 页面用例并收集截图 |
| 版本 | 基础版本与 `versionCode` 统一维护在根目录 `version.properties`；产物使用 `0.0.x+<实际提交前7位>` |

MCP 历史功能由此前未进入主线的分支改动整理集成。本轮验证以当前源码为准，不沿用该分支或旧版的通过状态。

## 构建与产物

本轮检查命令：

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 本地 Gradle | 完整检查通过，56 秒 | [构建日志](../artifacts/validation/v0.0.2-d58b2f4/gradle-validation.txt) |
| JVM | 核心 125 项、App 75 项，共 200 项；0 失败、0 错误、0 跳过 | [汇总](../artifacts/validation/v0.0.2-d58b2f4/summary.json) |
| Android Lint | 0 错误、42 条警告 | [XML 报告](../artifacts/validation/v0.0.2-d58b2f4/lint-results-debug.xml) |
| ARM64 APK | 签名、ABI、包名与版本检查通过 | [产物元数据](../artifacts/validation/v0.0.2-d58b2f4/summary.json) |
| GitHub Actions 常规构建 | 成功，3 分 30 秒；已上传 APK 与报告 | [运行 45986099940](https://github.com/Ktclat/pathpilot/actions/runs/45986099940) |
| KVM 模拟器 CI | 成功，5 分 17 秒；7 项通过，0 失败、0 跳过、0 未完成 | [运行 45990106903](https://github.com/Ktclat/pathpilot/actions/runs/45990106903) · [结果](../artifacts/screenshots/ci-d3977fe-45990106903-1/test-results.json) |
| 本地模拟器 | 计划 7 项：2 项通过、1 项失败、4 项未完成 | [批次与诊断](../artifacts/screenshots/v0.0.2-d58b2f4/README.md) |
| Release | 本轮尚未发布 | — |

产物为 `artifacts/PathPilot-0.0.2+d58b2f4-arm64.apk`，包名 `dev.pathpilot.app`，大小 21,226,961 字节。SHA-256：`4140a61c7fa9043ebbc12ee742acca30d292834f4c5c74941f3b9d8f5d68ab11`。

常规交付 APK 仅包含 `arm64-v8a`。模拟器截图包与常规安装包分开记录，不互相替代验证结果。

先前尝试中的 App 测试夹具清理、测试宿主的 `StateFlow.value` Lint 错误及无效断言 import 均已修正。夹具未取消持续等待任务曾导致测试虚拟调度持续推进，进程被终止（退出码 143）；[中断记录](../artifacts/validation/v0.0.2-d58b2f4/initial-test-fixture-stop.txt)、[线程转储](../artifacts/validation/v0.0.2-d58b2f4/initial-test-worker-thread-dump.txt)、[Lint](../artifacts/validation/v0.0.2-d58b2f4/initial-ui-test-lint.txt)和[编译记录](../artifacts/validation/v0.0.2-d58b2f4/initial-ui-test-compile.txt)保留。最终通过结果以上表为准。

## 设备与接口验证范围

最终 KVM 批次使用源码 [`d3977fe`](https://github.com/Ktclat/pathpilot/commit/d3977fefd677801cc64d99ac185b812b6d6b819e)，截图包版本为 `0.0.2+d3977fe` / code 14；生产 App、core、资源、构建与版本配置均与交付源码 `d58b2f4` 相同，差异为测试、CI 和证据。APK 哈希、API 35 / KVM 设备信息与原始 runner 见[批次记录](../artifacts/screenshots/ci-d3977fe-45990106903-1/README.md)。

| KVM 用例 | 项数 | 结果与范围 |
| --- | ---: | --- |
| Codex 登录 | 3 | 全部通过：Activity 重建和短暂网络失败保留设备码、过期后重新授权、浏览器往返后的生命周期及设备码断言 |
| MCP 页面与服务 | 1 | 通过：连接配置、获取与选择工具、独立共享、调用记录与筛选；请求发往设备回环 HTTP 上游 |
| SQLite 历史 | 3 | 全部通过：按开始时间保留最新 1,000 条、持久化与去重、写入失败可见且重试不丢失或重复记录 |

认证 UI 测试挂载生产登录组件与 Activity 作用域 ViewModel，授权会话和账户保存使用本地夹具；浏览器打开设备回环页面。真实 Codex 账号授权、订阅请求和 GitHub 工具调用尚未联调。

### 先前尝试

本地批次使用 `d58b2f4` 截图包，测试 APK 额外包含[两处令牌字段文案匹配修正](../artifacts/validation/v0.0.2-d58b2f4/instrumentation-label-adjustments.patch)。结果为 2 项登录用例通过、1 项浏览器往返失败、MCP 流程中断和 3 项 SQLite 未开始，共 4 项未完成。浏览器失败时未完成返回后断言，日志随后记录 Browser2 renderer `SIGTRAP`；平台连接中断时 MCP runner 没有终态。原始结果与包哈希保留在[本地批次](../artifacts/screenshots/v0.0.2-d58b2f4/validation-summary.json)，不被 KVM 成功结果覆盖。

本地启动诊断另记录 Quickstep/System UI 弹窗及 PathPilot 首次启动未绘制首帧；同一次应用启动随后自行完成绘制，没有重启应用。这些记录不构成 PathPilot ANR 的结论，见[启动诊断](../artifacts/screenshots/v0.0.2-d58b2f4/startup-diagnostics/metadata.json)。

首轮 KVM 工作流在启动模拟器前失败，7 项均未完成；将固定版本 `avdmanager` 安装到 SDK 内后，第二轮完整通过。[首轮结果](../artifacts/screenshots/ci-53459f9-45989059992-1/test-results.json)单独保留。

### 截图审查

KVM 批次保留 9 张内部截图与 8 张外部截图。[MCP 历史页](../artifacts/screenshots/ci-d3977fe-45990106903-1/screenshots/internal/13-mcp-history.png)已审查，筛选、统计及次级页隐藏主导航符合当前实现。静态登录界面使用本地批次的[浏览器登录图](../artifacts/screenshots/v0.0.2-d58b2f4/auth-ui-final/43-codex-browser-login.png)。

云批次的 `50` 仍显示 Browser2 网页，不能用作返回后的 App UI 截图；浏览器往返通过的依据是 runner 生命周期与设备码断言。云 `43` 和 MCP 编辑器 `09` 分别包含淡入与输入法过渡，保留原图，不用于证明静态布局。图片与审查范围见[云批次说明](../artifacts/screenshots/ci-d3977fe-45990106903-1/README.md)。

## 待验证事项

- GitHub MCP 当前使用 PAT；接入 GitHub OAuth 仍缺少 PathPilot 对应 OAuth 应用的 client ID。Microsoft Learn 与 OpenAI Docs 预设无需凭据，Context7 使用 API Key。具体接入方式见 [手机 MCP 文档](android-mcp.md)。
- 远程 MCP 的已有在线尝试在云代理 CONNECT 阶段返回 403，未到达服务。本轮尚未取得新的远程握手结果，来源与范围见 [预设研究](mcp-presets.md)。
- 上一轮主题自动流程的空闲超时、一次启动 ANR 及模拟器深色三键导航低对比均保留在[历史记录](validation-0.0.1-ac963f2.md)，当前没有证据将它们标为已修复。
