# 0.0.2+d58b2f4：本地 Android 验证

计划 7 项：**2 项通过，1 项失败，4 项未完成**。未完成项包括环境中断的 MCP 流程 1 项，以及尚未开始的 SQLite 测试 3 项。完整计数、截图 SHA-256 和包信息见 [validation-summary.json](validation-summary.json)。后续 KVM 批次单独记录，不覆盖本次结果。

## 构建与设备

- 生产源码：`d58b2f448152c8b0c499709fad974577446cc519`，版本 `0.0.2+d58b2f4`，code 14。
- 截图 APK：`a7e16439e07e53d4980a8996810606b894e2e5c9e2ad8c173d2d0744c66ead94`。
- 实际执行的测试 APK：`59e0bcc311f1f5dd2b454441beacd53f7ce70f7a5496f467744314f7a0f4a4c4`。
- 测试源码额外包含两处令牌字段文案匹配修正：[补丁](../../validation/v0.0.2-d58b2f4/instrumentation-label-adjustments.patch)，SHA-256 为 `c9160af96c3fa1a6ef26201cae4c7ebb3a85e7519d1f20894aed3aad68919507`。生产 APK 未改变。各批次的 `source-files.json` 来自固定提交，`test-source-files.json` 叠加这两个测试文件的 SHA。
- API 35 / AOSP x86_64，软件 TCG；720×1280、density 320（360dp）、font scale 1.0、系统浅色。使用独立 `dev.pathpilot.app.capture` 与 `.capture.test`；截图包不是 ARM64 交付 APK。

## 用例结果

| 批次 | 用例 | 结果与范围 |
| --- | --- | --- |
| [auth-ui-final](auth-ui-final/results.json) | `deviceCodeAndNetworkRecoverySurviveActivityRecreation` | 通过。真实 Activity 重建后复用同一个 ViewModel 与设备码；短暂断网后使用原 challenge 恢复等待，没有再次申请设备码。 |
| [auth-ui-final](auth-ui-final/results.json) | `expiredDeviceAuthorizationShowsReadableErrorAndCanRestart` | 通过。显示可读超时提示；点击重新授权后获取新设备码。 |
| [browser-return-final](browser-return-final/results.json) | `browserRoundTripRetainsThePendingDeviceCode` | 失败。浏览器收到本地请求、宿主 Activity 已停止；系统 Back 后 30 秒内未恢复 `RESUMED`，失败位于测试第 211 行。后续日志记录 Browser2 的 WebView renderer `SIGTRAP`，并导致 Browser2 崩溃。没有完成返回后设备码断言。 |
| [mcp-history-final](mcp-history-final/interruption.json) | `connectionToolsAndIndependentSharingReachAnActualHttpUpstream` | 环境中断。runner 只有开始状态；连接恢复后模拟器与 runner 进程已消失，没有通过或失败的终态。 |
| `McpCallHistoryStoreTest` | 3 项 SQLite 测试 | 未开始。 |

认证 UI 测试挂载生产 `CodexLoginDialog`，使用 Activity 的 `ViewModelStore`；仅授权会话和保存账户为本地夹具。浏览器往返打开设备回环 HTTP 页面。这里没有执行真实 OpenAI 账号授权或令牌保存。

## 截图索引

所有 PNG 保留实际窗口原始像素。43–49 由通过的仪器测试导出；其余明确标为手动诊断或预热截图。

| 截图 | 内容 |
| --- | --- |
| [43：浏览器登录](auth-ui-final/43-codex-browser-login.png) | 默认浏览器模式、打开登录页面、切换设备码。 |
| [44：等待设备码授权](auth-ui-final/44-codex-device-waiting.png) | 本地测试设备码、复制和浏览器入口。 |
| [45：Activity 重建后](auth-ui-final/45-codex-device-after-recreation.png) | 保留原设备码与等待状态。 |
| [46：连接中断](auth-ui-final/46-codex-device-reconnecting.png) | 显示使用原验证码重连。 |
| [47：连接恢复](auth-ui-final/47-codex-device-recovered.png) | 恢复等待，设备码未更换。 |
| [48：授权超时](auth-ui-final/48-codex-login-expired.png) | 错误说明及重新授权动作。 |
| [49：重新授权](auth-ui-final/49-codex-login-restarted.png) | 用户重新授权后显示新的测试设备码。 |
| [Browser2 诊断图](browser-return-final/diagnostic-current-window.png) | 浏览器显示回环地址；不是往返测试通过图。详见 [诊断记录](browser-return-final/diagnostic-evidence.json)。 |
| [生产 MCP 根页](mcp-history-final/diagnostic-current-window.png) | 手动截取运行中的真实 MainActivity，显示四项导航及调用记录入口；不代表完整 MCP 流程通过。 |
| [生产共享根页](warm-start/01-sharing-light.png) | 原启动最终完成绘制后的真实 MainActivity。与 `startup-diagnostics/main-launch-later.png` 为同一张原图。 |

未补拍真实 MainActivity 中的 Codex 登录弹窗及 About 版本页；不能用夹具宿主或包元数据冒充这两张截图。

## 启动异常与中断记录

[startup-diagnostics](startup-diagnostics/metadata.json) 保留应用测试前的 Quickstep、System UI 无响应弹窗、首次主界面启动命令超时，以及随后出现正常共享页的原图。没有重启 PathPilot 来覆盖这次启动；系统事件与应用 PID 日志分别保留。

- [Quickstep 弹窗](startup-diagnostics/browser-warm-current.png)
- [System UI 弹窗](startup-diagnostics/browser-after-close-dialog.png)
- [首次主界面尚未绘制](startup-diagnostics/main-launch-current.png)
- [同一次主界面启动完成绘制](startup-diagnostics/main-launch-later.png)
- [Browser2 崩溃日志](browser-return-final/diagnostic-browser-logcat.txt)
- [MCP 批次环境中断](mcp-history-final/interruption.json)

连接恢复后未再次启动本地模拟器。设备数据清理与 density 覆盖值恢复没有执行，详见 [cleanup.json](cleanup.json) 和 [原设备值](startup-diagnostics/original-device.json)；正常 `dev.pathpilot.app` 未被操作。
