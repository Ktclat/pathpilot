# Codex 模型获取：离线 UI 集成测试

`CodexModelsFlowTest` 使用生产 `ProvidersScreen`、`ProviderEditor`、`ProviderEditorViewModel`、`CodexModelsClient` 与 `ProviderRoutes.addSelected`。只替换凭据解析来源和 HTTP 目的地：凭据是测试常量，OkHttp interceptor 把 Codex 模型目录请求送到 Android 设备内的 MockWebServer 回环端口。请求经过真实 HTTP 连接和生产 JSON 解析，测试不会联系 ChatGPT，也不读取设备里保存的账号。

测试覆盖已登录入口获取目录、选择模型并保存路由，401 失败显示与重试，以及未登录时进入登录且不发送目录请求。目录中的模型 ID 是固定 mock 数据，不表示真实账号的可用模型；`supported_in_api=false` 的订阅目录项仍应可选。所有 fixture 只存在于 androidTest APK。

运行（截图专用 x86_64 包，不能作为常规 ARM64 交付包）：

```sh
./gradlew -PuiCapture=true :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.pathpilot.app.ui.providers.CodexModelsFlowTest
```

测试直接挂载真实供应商页面和编辑器，不启动后台共享服务，也不测试账号登录协议。三个 PNG 来自 UiAutomation 对实际运行界面的截图，写入目标应用 externalFilesDir 下的 `codex-models-e2e/`：

- `01-provider-entry.png`：已登录供应商的获取模型入口。
- `02-model-selection.png`：回环 HTTP 成功后选择一个模型。
- `03-auth-error.png`：回环 HTTP 返回 401 的错误状态，之后继续测试重试成功。

截图展示的是明确离线 mock 场景，不是实际订阅账号结果。测试宿主仅负责页面切换与真实路由更新回调；不增加产品界面 mock 标记或生产测试数据。文件存在不代表本次测试通过，应同时核对当前 instrumentation 结果和截图时间。
