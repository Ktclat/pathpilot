# Android 设备测试与截图

设备测试运行生产 Compose 页面、Android 存储和服务；上游数据使用离线夹具或设备内的 MockWebServer。实际批次结果与原图见 [验证记录](../../../docs/validation.md) 和 [截图索引](../../../artifacts/screenshots/README.md)。

## 构建与设备准备

云端模拟器使用独立 `dev.pathpilot.app.capture` 应用和 `dev.pathpilot.app.capture.test` 测试包。`uiCapture=true` 只启用 x86_64 debug 截图构建，常规交付 APK 仍只有 ARM64。截图构建复用 APK 输出目录，开始前先保存已验证的常规 ARM64 APK。

```sh
./gradlew -PuiCapture=true :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

以下设备配置命令对应 Android 15 / API 35、720×1280 模拟器：density 320 为 360dp，density 360 为 320dp。修改前在同一 shell 保存原值，结束后按本文末尾恢复。

```sh
capture_density_override="$(adb shell wm density | sed -n 's/^Override density: //p' | tr -d '\r')"
capture_font_scale="$(adb shell settings get system font_scale | tr -d '\r')"
adb shell wm density 320
adb shell settings put system font_scale 1.0
```

## 用例范围

下表类名均以 `dev.pathpilot.app.` 为前缀。截图目录相对于目标应用的 `externalFilesDir`。

| 测试类 | 用例数 | 验证对象 | 截图目录 |
| --- | ---: | --- | --- |
| `ui.providers.CodexModelsFlowTest` | 3 | 生产 `ModelRoutesPage`、供应商编辑器和目录客户端：登录入口、获取/选择/保存模型、401 与重试；仅替换凭据来源和 HTTP 目的地 | `codex-models-e2e/` |
| `ui.providers.ApiModelsFlowTest` | 1 | 生产 `MainActivity`：从 API 供应商模型页点击一次即获取目录，核对 HTTP 请求、选择模型并保存路由 | `settings-e2e/`（23、24、25） |
| `ui.models.ProviderModelsFlowTest` | 3 | 供应商一级、模型二级、搜索/启用筛选、供应商开关保留模型开关 | `provider-models-e2e/` |
| `ui.statistics.StatisticsFlowTest` | 5 | 统计组件的模型/供应商下钻、状态和搜索筛选、清空确认、HTTP 200 中断、未知及零 Token | `statistics-e2e/` |
| `ui.statistics.StatisticsNavigationTest` | 1 普通 + 1 大字体 | 生产 `MainActivity`：四项主导航、从模型/最近请求打开统计并返回，以及放大字体布局 | `statistics-e2e/` |
| `ui.statistics.StatisticsRestorationTest` | 1 | 趋势选择恢复时，保存索引超出缩短后的时间桶列表 | — |
| `runtime.RequestHistoryStoreTest` | 6 | 真实 SQLite：重开、去重、保留 1,000 条、清空、写入失败重试和损坏记录 | — |
| `settings.BackupStoreTest` | 5 | Android Keystore / AtomicFile：加密恢复、可选会话、保留本机连接设置、写入原子性、损坏配置和迁移 | — |
| `ui.settings.SettingsFlowTest` | 3 普通 + 1 大字体 | 生产 `MainActivity`：四页导航及返回/滚动状态、主题和语言持久化、原生文件器、放大字体设置/MCP | `settings-e2e/` |
| `ui.settings.BackupFlowTest` | 1 | 生产设置页面/ViewModel 与真实加密文件往返；仅替换文件选择结果 URI | `settings-e2e/` |
| `ui.settings.McpFlowTest` | 1 | 生产页面、前台服务与真实回环 HTTP：连接、工具选择、鉴权、允许/拒绝调用、模型/MCP 独立启停和通知入口 | `settings-e2e/` |

`CodexModelsFlowTest` 的订阅目录为固定测试数据，未执行真实账号登录。`BackupFlowTest` 的文件 URI 替换不覆盖 Android 文件选择器；原生 `CreateDocument` 由 `SettingsFlowTest#exportUsesTheAndroidDocumentPickerAndCanBeCancelled` 单独验证。SQLite 和备份存储测试使用独立目录；涉及应用单例的设备流程限制在 `.capture` 包。

## 四页导航回归：普通字体 10 项

当前主导航是「共享 / 模型 / MCP / 设置」，统计从模型顶栏及请求入口打开；供应商模型详情、MCP 连接详情和统计隐藏主导航。以下两批共选择 10 项，与本轮导航、供应商合并和公共控件的变化对应。

设置和 MCP 用例在启动 Activity 前通过 AppStore 及平台语言接口设置中文。先运行这 3 项：

```sh
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.ui.settings.SettingsFlowTest#fourPrimaryPagesPreserveListsAndUseSecondaryDetails,dev.pathpilot.app.ui.settings.SettingsFlowTest#themeMonetAndLanguageChoicesPersistInTheNativeActivity,dev.pathpilot.app.ui.settings.McpFlowTest' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

以下 Codex、供应商模型及统计用例使用中文标签，不自行设置初始语言。运行前显式设置应用语言；主题/语言用例可能已切换为 English，不能依赖上批结束时的状态。以下命令适用于 Android 13+：

```sh
adb shell cmd locale set-app-locales dev.pathpilot.app.capture --user 0 --locales zh-CN
adb shell cmd locale get-app-locales dev.pathpilot.app.capture --user 0
adb shell am force-stop dev.pathpilot.app.capture
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.ui.statistics.StatisticsNavigationTest#statisticsOpensFromModelsAndReturnsToItsSource,dev.pathpilot.app.ui.providers.CodexModelsFlowTest,dev.pathpilot.app.ui.models.ProviderModelsFlowTest' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

第二批为统计导航 1 项、Codex 模型目录 3 项、供应商模型 3 项。

备份加密、系统文件器、完整统计组件、趋势状态恢复及持久历史不包含在这 10 项中；相关实现变化时，按表选择对应类或方法运行。例如：

```sh
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.settings.BackupStoreTest,dev.pathpilot.app.ui.settings.BackupFlowTest,dev.pathpilot.app.ui.settings.SettingsFlowTest#exportUsesTheAndroidDocumentPickerAndCanBeCancelled' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

## 大字体：2 项

单独设置真实系统字号和宽度，再运行设置/MCP、共享/统计两项布局检查：

```sh
adb shell wm density 360
adb shell settings put system font_scale 1.5
adb shell cmd locale set-app-locales dev.pathpilot.app.capture --user 0 --locales zh-CN
adb shell am force-stop dev.pathpilot.app.capture
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.ui.settings.SettingsFlowTest#largeSystemFontKeepsSettingsAndMcpControlsReachable,dev.pathpilot.app.ui.statistics.StatisticsNavigationTest#largeSystemFontKeepsNavigationMetricsAndTrendReachable' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

检查四个导航标签的文字边界、地址分段按钮等高、备份和 MCP 编辑控件可达，以及统计指标、趋势和长模型名。统计大字体用例在配置不符时会跳过，设置大字体用例会断言失败；专门验收时须确认两项均有通过状态。

## API 模型自动获取：定向回归 4 项

`ee2b4dd` 包含 API 供应商自动获取修复，以及编辑器 Dialog 系统栏明暗修复。API 供应商在二级模型页点击「获取模型」后直接读取目录，无需在编辑器再点一次；Codex 仍要求已登录。重新构建并安装两个截图 APK 后，运行新增的 API 用例及原 Codex 3 项，并核对编辑器截图中的系统栏：

```sh
adb shell wm density 320
adb shell settings put system font_scale 1.0
adb shell cmd locale set-app-locales dev.pathpilot.app.capture --user 0 --locales zh-CN
adb shell am force-stop dev.pathpilot.app.capture
adb shell am instrument -w -r -e class \
  'dev.pathpilot.app.ui.providers.ApiModelsFlowTest,dev.pathpilot.app.ui.providers.CodexModelsFlowTest' \
  dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner
```

`ApiModelsFlowTest` 在启动 Activity 前自行设置中文并保存测试前配置；结束时恢复配置。它通过设备回环 HTTP 核对 `GET /v1/models`、Bearer 请求头及请求次数为 1，再选择目录模型并确认路由保存。原图位于 `settings-e2e/23-api-provider-models.png`、`24-api-model-selection.png` 和 `25-api-model-saved.png`。

四页导航的 10 项普通字体和 2 项大字体属于 `4e19d6e` 的验证范围；这 4 项属于修复后的独立批次，须各自记录 APK 和源码版本。其中 Codex 3 项是回归复跑，不重复计入独立用例总数。

## 截图与结果归档

PNG 由 `UiAutomation.takeScreenshot()` 从实际运行窗口导出，保留原始像素。拉取示例：

```sh
mkdir -p artifacts/screenshots/local-run
adb pull /sdcard/Android/data/dev.pathpilot.app.capture/files/settings-e2e artifacts/screenshots/local-run/
adb pull /sdcard/Android/data/dev.pathpilot.app.capture/files/statistics-e2e artifacts/screenshots/local-run/
adb pull /sdcard/Android/data/dev.pathpilot.app.capture/files/codex-models-e2e artifacts/screenshots/local-run/
adb pull /sdcard/Android/data/dev.pathpilot.app.capture/files/provider-models-e2e artifacts/screenshots/local-run/
```

各测试目录会复用图片名。每批运行前先归档并清空上一批截图目录，结束后保存当批原图、完整 runner 输出、逐例结果、源码提交/未提交改动、两个 APK 的 SHA-256，以及系统版本、ABI、尺寸、density 和 font_scale。图片存在或 `adb` 进程退出码为 0，均不能替代用例通过状态；统计时区分通过、失败和跳过。

先查看截图，再记录 UI 结论。系统弹窗遮挡、ANR 或失败批次保留原始证据；修复后只重跑受影响用例，用新目录记录，不覆盖首次失败。共享密钥默认隐藏，夹具留在测试 APK，不把截图包作为 ARM64 交付产物。

结束后恢复本次修改的设备配置，并在完成归档后清理测试数据：

```sh
if [ -n "$capture_density_override" ]; then
  adb shell wm density "$capture_density_override"
else
  adb shell wm density reset
fi
if [ "$capture_font_scale" = null ]; then
  adb shell settings delete system font_scale
else
  adb shell settings put system font_scale "$capture_font_scale"
fi
adb shell am force-stop dev.pathpilot.app.capture
adb shell pm clear dev.pathpilot.app.capture
adb shell pm clear dev.pathpilot.app.capture.test
```

仓库级构建、截图和提交要求见 [贡献指南](../../../CONTRIBUTING.md)。
