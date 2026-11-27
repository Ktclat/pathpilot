# 0.0.1-aff1629：主题与 MCP 首次设备验证

本目录保留首次验证的失败与手动对照。源码为 [`aff1629`](https://github.com/Ktclat/pathpilot/commit/aff16297225dadd2bc505590db53929cbcf5f763)，截图包与测试包的 SHA、设备参数和逐例结果见 [normal-attempt1/results.json](normal-attempt1/results.json)。本批不能作为流程通过的依据；最终结果见[当前验证记录](../../../docs/validation.md)。

## 首批结果

Android 15 / API 35，720×1280，density 320，font scale 1.0，独立 `.capture` 应用。原定 4 项，实际启动 3 项、均失败，进程崩溃后第 4 项未运行。

| 用例 | 实际结果 |
| --- | --- |
| 主题与语言 | 点击主题后，等待 Compose 空闲超时 |
| 免凭据 MCP 预设 | 已执行添加、503 反馈和重试；后续绘制发生 SnapshotStateObserver 跨线程异常 |
| 令牌 MCP 预设 | 已执行 GitHub 首次添加；销毁页面时 SlotWriter 越界并使进程崩溃 |
| 自定义 MCP 与共享 | 未运行 |

[runner 原文](normal-attempt1/instrumentation.log)和 [logcat](normal-attempt1/logcat-after.txt)保留完整失败。后者记录了 `DefaultDispatcher-worker-4` 经测试 `ApplyingContinuationInterceptor` / `TestMonotonicFrameClock` 执行 `Recomposer.applyChanges`，与主线程布局产生冲突。修正只将本轮三个测试类改用 Compose UI Test 1.12.1 官方 `junit4.v2.createEmptyComposeRule` 的排队调度，没有修改产品、断言、空闲等待或超时。主题首次超时是否同源需要后续复验。

## 原始图片

| 场景 | 原图 |
| --- | --- |
| 设置中的主题入口 | [26-settings-theme-entry.png](normal-attempt1/26-settings-theme-entry.png) |
| 远程预设目录 | [37-mcp-preset-catalog.png](normal-attempt1/37-mcp-preset-catalog.png) |
| 夹具返回 503 | [38-mcp-preset-fetch-error.png](normal-attempt1/38-mcp-preset-fetch-error.png) |
| 重试后读取工具 | [39-mcp-preset-tools.png](normal-attempt1/39-mcp-preset-tools.png) |
| GitHub 令牌与工具 | [令牌表单](normal-attempt1/40-mcp-github-token.png) · [工具列表](normal-attempt1/41-mcp-github-tools.png) |

预设测试在测试 Activity 挂载生产 MCP 页面，真实 HTTP 客户端重定向至设备回环服务器；工具和凭据为夹具。图片产生不代表整项测试通过，也不代表外部 MCP 服务联调成功。

## 正常启动对照

同一 APK 在测试框架外冷启 MainActivity，手动打开主题面板，等待 15 秒后取得[主题面板原图](manual-theme-diagnosis/02-theme-open-steady.png)。明暗选项、动态取色、五种色板和完成按钮正常显示，无系统弹窗遮挡。另保留[设置页](manual-theme-diagnosis/01-settings-before-theme.png)、[窗口状态](manual-theme-diagnosis/02-theme-window.txt)和[共享主页](warm-start/01-sharing-light.png)。手动显示正常不替代完整交互测试。

[environment-startup](environment-startup/metadata.json)单独记录安装新包前的模拟器启动及系统弹窗，不计为本轮产品测试。
