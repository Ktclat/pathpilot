# v0.1.8 用量与请求：模拟器验证

2026-11-25，在 Android 15 / API 35 软件模拟器执行一次完整 instrumentation，**OK (9 tests)**，耗时 **194.322 秒**。三个页面用例使用生产 `UsageRequestsPage` 和明确标注的离线请求元数据；六个存储用例使用设备真实 SQLite 的独立 UUID 测试目录，不访问真实订阅账号。

## 原始截图

以下三张 720×1280 PNG 由 Android `UiAutomation.takeScreenshot()` 生成，未编辑像素，已逐张实际查看，均没有系统弹窗遮挡。

| 文件 | 内容 |
| --- | --- |
| [01-model-usage.png](01-model-usage.png) | 用量概览、输入/输出 Token、返回用量的请求占比及模型汇总。 |
| [02-recent-requests.png](02-recent-requests.png) | 请求搜索、状态筛选、供应商、时间和耗时。 |
| [03-request-details.png](03-request-details.png) | 上游模型、接口、流式状态、HTTP 200 与中断终态、未提供的 Token。 |

页面 fixture 共四条请求：两条成功、一条失败、一条中断，仅一条提供 Token（输入 1,200、输出 180、合计 1,380）。这些数字用于验证显示与聚合，不代表真实账号用量。

## 验证范围

| 测试类 | 数量 | 检查 |
| --- | --- | --- |
| `UsageRequestsFlowTest` | 3 | 按模型打开请求；HTTP 200 中断和缺失 Token 详情；搜索、状态筛选及清空确认。 |
| `RequestHistoryStoreTest` | 6 | 跨实例重读、ID 去重、保留最新 1,000 条、清空持久化、写入失败重试、损坏记录的错误呈现。 |

页面挂载在测试 `ComponentActivity` 中，验证生产 Compose 组件与回调，不等同于完整 AppStore 主页面导航。SQLite 测试使用真实设备数据库，结束后删除各例测试目录；网络观测由 core 回环 HTTP 测试覆盖。

原始 [instrumentation.log](instrumentation.log) 和 [instrumentation-results.json](instrumentation-results.json) 记录 runner、逐例状态、当次图像 mtime、字节数及 SHA-256。全部九例均返回 `INSTRUMENTATION_STATUS_CODE: 0`，三图来自同一次运行。

## 测试包与环境

| APK | SHA-256 |
| --- | --- |
| `PathPilot-0.1.8-ui-capture.apk` | `effce74033079d9ab341afcacadd053b6db3caf4635ecc3a4fea200a19618e44` |
| `PathPilot-0.1.8-ui-tests.apk` | `ccbe81afc5d88c8384087a072d4f5eb4bab98961099985c8b33f888e258a710d` |

Runner：`dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner`。正常交付包仍为 ARM64；本目录使用独立 x86_64 截图包及 androidTest 包。

主机没有 KVM。首次命令缺少 emulator 要求的 `ANDROID_I_WANT_MY_TCG=yes`，在 VM 启动前退出；随后补充该变量启动一次无快照的软件模拟器。安装最终 APK 后 speed 预编译并预热生产 MainActivity。准备阶段先后出现 Quickstep、System UI 无响应弹窗，均实际查看、分别关闭一次并确认首页清晰后才开始九项测试。

[启动诊断](diagnostics/startup/) 保留两张弹窗原图及正式测试前的共享首页；没有重启运行中的模拟器，也没有重复执行本轮九项测试。

结束后已清空 capture 与 androidTest 两个测试包的数据（均为 `Success`），并停止本次模拟器；ADB 已无连接设备。生产包数据未操作，主机上的 APK、AVD 及原始证据保留。[清理记录](diagnostics/startup/cleanup.json)。
