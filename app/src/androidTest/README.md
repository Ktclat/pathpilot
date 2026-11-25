# Codex 模型获取：离线 UI 集成测试

本文早期供应商、模型与统计用例使用中文控件标签。v0.1.10 开始支持应用语言；运行这些旧类前，先安装 `.capture` 包并选择简体中文。Android 13+ 可用平台命令（已在 API 35 核对 `cmd locale help`）：

```sh
adb shell cmd locale set-app-locales dev.pathpilot.app.capture --user 0 --locales zh-CN
adb shell cmd locale get-app-locales dev.pathpilot.app.capture --user 0
```

Android 8–12 在 App 设置中选择「语言 → 简体中文」。设置/MCP 新用例自行通过 AppStore 与原生 `LocaleManager.applicationLocales` 设置初始语言，不依赖这一手动前置。

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

## 设置、备份与 MCP

v0.1.10 新增测试均要求隔离的 `.capture` 包，不访问真实账号或外部 MCP：

- `settings.BackupStoreTest`：5 项，Android Keystore/AtomicFile 的导出恢复、可选订阅登录状态、保留本机连接配置、写入失败原子性、损坏配置恢复及旧版迁移。使用独立 `noBackupFilesDir`，不改生产单例。
- `ui.settings.SettingsFlowTest#themeMonetAndLanguageChoicesPersistInTheNativeActivity`：生产 MainActivity 的主题、莫奈取色、中英切换及 Activity 重建后保留。
- `ui.settings.SettingsFlowTest#exportUsesTheAndroidDocumentPickerAndCanBeCancelled`：生产 MainActivity 实际打开 Android CreateDocument 文件器，截图并取消。
- `ui.settings.BackupFlowTest`：生产 SettingsScreen/ViewModel 和真实加密文件读写。测试只替换 ActivityResultRegistry 返回的私有文件 URI；验证错误密码不改配置、恢复前预览及默认保留本机端口。该项不替代上面的系统文件器检查。
- `ui.settings.McpFlowTest`：生产 MainActivity 添加 Bearer 连接、实际获取工具、禁用工具、启动独立前台共享服务；通过真实 HTTP 客户端验证 initialize/list/call、工具白名单及停用 MCP 后模型服务继续可用。上游是测试进程内的 MockWebServer。

先按上文构建安装 capture APK，然后以默认 360dp / font_scale=1.0 运行 9 项：

```sh
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.settings.BackupStoreTest,dev.pathpilot.app.ui.settings.SettingsFlowTest#themeMonetAndLanguageChoicesPersistInTheNativeActivity,dev.pathpilot.app.ui.settings.SettingsFlowTest#exportUsesTheAndroidDocumentPickerAndCanBeCancelled,dev.pathpilot.app.ui.settings.BackupFlowTest,dev.pathpilot.app.ui.settings.McpFlowTest' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

大字体检查单独运行，真实设置为 320–360dp 和 font_scale=1.5；配置不符合时用例失败，不会跳过计入通过：

```sh
adb shell wm density 360
adb shell settings put system font_scale 1.5
adb shell am force-stop dev.pathpilot.app.capture
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.ui.settings.SettingsFlowTest#largeSystemFontKeepsSettingsAndMcpControlsReachable' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

原图位于 `externalFilesDir/settings-e2e/`。测试结束恢复此前记录的 density/font_scale，并清理隔离包。语言用例启动 Activity 前同时设置 AppStore 和 Android 13+ 的应用 LocaleManager；用例中的用户语言切换走实际设置界面。

## MCP 调用记录

`dev.pathpilot.app.runtime.McpCallHistoryStoreTest` 使用真实 Android SQLite 和独立数据库，覆盖重新打开、去重、清空、写失败重试及 1,000 条上限。现有 `McpFlowTest` 还验证停止共享后能打开并筛选之前的调用；新增实际截图写入调试包内部 `files/settings-e2e/13-mcp-history.png`，可在隔离测试设备上通过 `adb exec-out run-as dev.pathpilot.app.capture cat files/settings-e2e/13-mcp-history.png` 导出。均不需要真实订阅、外部 MCP 服务或其他设备。
