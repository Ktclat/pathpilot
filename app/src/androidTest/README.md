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

## 按供应商管理模型

`dev.pathpilot.app.ui.models.ProviderModelsFlowTest` 挂载生产 `ModelRoutesPage`，只由测试持有本地配置与选中供应商 ID。覆盖一级供应商、二级模型隔离和返回；共享名称/上游 ID 搜索、启用筛选、单模型切换；供应商总开关关闭及恢复时保留每个模型设置。

该流程无需网络或账号。原始截图写入测试包的 `externalFilesDir/provider-models-e2e/`：`01-provider-groups.png`、`02-provider-models.png`、`03-provider-disabled.png`。它验证真实页面及状态回调，不包含 AppStore 跨重启持久化或真实上游模型可用性。


## 用量、请求详情与持久历史

`dev.pathpilot.app.ui.usage.UsageRequestsFlowTest` 挂载生产 `UsageRequestsPage`，提供明确标注的离线请求元数据。三项流程覆盖按模型进入对应请求、HTTP 200 中断与未知 Token 详情，以及搜索/状态筛选和清空确认。原始 `UiAutomation` 截图位于 `externalFilesDir/usage-requests-e2e/`。

`dev.pathpilot.app.runtime.RequestHistoryStoreTest` 使用设备真实 SQLite 和独立 UUID 测试目录，六项检查覆盖跨实例重读、ID 去重、最新 1,000 条保留、清空、写入失败后重试、损坏记录的错误呈现。各例结束删除其测试目录，不操作用户实际数据库。

```sh
./gradlew -PuiCapture=true :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.pathpilot.app.ui.usage.UsageRequestsFlowTest,dev.pathpilot.app.runtime.RequestHistoryStoreTest
```

两类共九项：它们分别验证真实 Compose 页面和真实 SQLite，不代表执行了真实订阅请求或 AppStore 完整主页面导航。网络观测由 core 中回环 HTTP 测试覆盖。结果须同时核对 runner、APK SHA 和当次截图，系统弹窗遮挡的图片不能作为界面展示图。
