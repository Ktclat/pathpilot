# v0.1.6 Codex 模型发现：离线组件联调

2026-11-25，在 Android 15 / API 35 x86_64 模拟器执行一次完整 instrumentation，结果 **OK (3 tests)**，耗时 113.222 秒。使用模拟订阅凭据与设备本机 MockWebServer；真实 Codex 账号及其实际模型目录尚未验证。

生产 `ProvidersScreen`、`ProviderEditor`、ViewModel、目录客户端和路由处理组件挂载在测试 `ComponentActivity` 中。仅凭据解析及 HTTP 目标由测试 fixture 提供；模型请求实际经过本机 HTTP，未向真实账号服务发送请求。截图中的“已登录”来自测试状态，供应商名称均明确包含“离线测试”。

## 构建与结果

| APK | SHA-256 |
| --- | --- |
| `PathPilot-0.1.6-ui-capture.apk` | `19a502292bfea043af7de6f2db6087adfe6771f2ebfd6c45fcd8c981dacadae2` |
| `PathPilot-0.1.6-ui-tests.apk` | `3fa88dbf71154a5eb932ada7ddc85969a0f9564b8a34d931e1b4c0848f91d343` |

实际 runner 为 `dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner`，测试类为 `dev.pathpilot.app.ui.providers.CodexModelsFlowTest`。原始 [instrumentation.log](instrumentation.log) 与 [instrumentation-results.json](instrumentation-results.json) 包含命令、时间、逐例结果及 PNG 的当次 mtime、大小和 SHA-256。

- 模拟登录态：从列表“获取模型”进入编辑器并自动获取目录，选择一个模型后保存，测试 `RouterConfig` 出现对应路由；这验证组件状态更新，不包括 AppStore 跨重启持久化。
- 模拟目录请求返回 401：显示登录过期提示；重试后重新解析测试凭据并成功获取模型。
- 未登录态：“获取模型”禁用，点击登录触发回调；不解析凭据、不请求目录。

三例分别返回 `INSTRUMENTATION_STATUS_CODE: 0`。开始前删除同名旧截图，结束后确认三张均由当次运行产生。

## 原始截图

以下 720×1280 PNG 由 Android `UiAutomation.takeScreenshot()` 生成，逐张实际查看过，未编辑像素。

| 文件 | 内容 |
| --- | --- |
| [01-provider-entry.png](01-provider-entry.png) | 模拟登录态的供应商列表，含“获取模型”入口。 |
| [02-model-selection.png](02-model-selection.png) | MockWebServer 返回两个 fixture 模型，已选一个；模型名称不代表真实账号的可用权限。 |
| [03-auth-error.png](03-auth-error.png) | 模拟 HTTP 401 后的重新登录提示。 |

## 环境与清理

主机没有 KVM，使用无快照的软件模拟。安装后对 target/test APK 执行 `cmd package compile -m speed -f`，本轮属于预热后的功能验证。环境准备阶段、运行测试前曾出现 SystemUI 无响应并关闭一次；没有重复重启模拟器。最初准备包没有运行测试；全部结果和截图均来自上表最终包。

结束后已清空 capture 与 instrumentation 测试包数据，并停止本次启动的模拟器；AVD 和主机上的原始证据保留，生产包数据未操作。
