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


## 统计、请求详情与主导航

`dev.pathpilot.app.ui.statistics.StatisticsFlowTest` 挂载生产 `StatisticsPage`，使用标有「离线测试」的请求元数据。五项流程覆盖模型下钻、HTTP 200 中断详情、搜索/状态筛选与清空确认、未知 Token 和真实零用量的区别，以及供应商下钻保留日期范围。

`dev.pathpilot.app.ui.statistics.StatisticsNavigationTest` 启动生产 `MainActivity`，验证五项底部导航、共享首页、独立统计空态和返回行为。大字体用例在真实系统 `font_scale=1.5`、320–360dp 宽度下验证本机/局域网按钮等高、导航文字无溢出，以及指标、趋势和长模型名称可通过滚动到达。该类只在独立 `.capture` 包写入离线请求元数据；每例结束清空对应测试数据库，不修改供应商或账号。

先构建并安装目标和测试 APK：

```sh
./gradlew -PuiCapture=true :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

默认字体下运行六项流程：

```sh
adb shell settings put system font_scale 1.0
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.ui.statistics.StatisticsFlowTest,dev.pathpilot.app.ui.statistics.StatisticsNavigationTest#statisticsIsAStandaloneDestinationAndSharingStaysFocused' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

随后单独运行真实大字体检查。在 720×1280 模拟器上，density=360 对应 320dp 宽度。操作前记录设备原始 density 与 font_scale，测试后恢复原值：

```sh
adb shell wm density
adb shell settings get system font_scale
adb shell wm density 360
adb shell settings put system font_scale 1.5
adb shell am force-stop dev.pathpilot.app.capture
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.ui.statistics.StatisticsNavigationTest#largeSystemFontKeepsNavigationMetricsAndTrendReachable' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

大字体用例在不符配置的普通测试运行中跳过；专门审查时必须核对其状态为通过，不能将跳过计为已验证。原始 `UiAutomation` 截图写入 `externalFilesDir/statistics-e2e/`，包括共享首页、统计概览、模型分组、请求和详情、空态，以及大字体共享页/指标/趋势/分组。截图无缩放或裁剪。

## 趋势状态恢复

`dev.pathpilot.app.ui.statistics.StatisticsRestorationTest` 使用 `StateRestorationTester` 保存生产 `StatisticsTrend` 的末桶选择，在销毁旧 composition 时将六个时间桶缩为一个，再恢复保存状态。断言唯一时间桶的请求读数、前后按钮禁用和 Token 切换，覆盖保存的索引大于新历史长度的恢复场景。只用离线时间桶，不访问 AppStore 或网络。

```sh
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.ui.statistics.StatisticsRestorationTest#restoredSelectionHandlesFewerTrendBuckets' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

## 持久历史

`dev.pathpilot.app.runtime.RequestHistoryStoreTest` 使用设备真实 SQLite 和独立 UUID 测试目录，六项检查覆盖跨实例重读、ID 去重、最新 1,000 条保留、清空、写入失败后重试、损坏记录的错误呈现。各例结束删除其测试目录，不操作用户实际数据库。

```sh
adb shell am instrument -w -r -e class \
  dev.pathpilot.app.runtime.RequestHistoryStoreTest \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

以上测试不代表真实订阅请求或账号余额结果。网络观测由 core 中回环 HTTP 测试覆盖。结果须同时核对 runner、APK SHA 和当次截图；系统弹窗遮挡的图片保留为异常证据，不能作为界面展示图。
