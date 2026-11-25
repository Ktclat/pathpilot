# v0.1.7 按供应商管理模型

2026-11-25，在 Android 15 / API 35 软件模拟器中运行生产 `ModelRoutesPage`、供应商分组页与模型列表页。测试 `ComponentActivity` 提供本地配置，供应商名称包含「离线测试」；不代表真实订阅账号或其模型权限。

## 原始截图

以下三张 720×1280 PNG 来自 Android `UiAutomation.takeScreenshot()`，未编辑像素，已逐张实际查看，均无系统弹窗遮挡。

| 文件 | 内容 |
| --- | --- |
| [01-provider-groups.png](01-provider-groups.png) | 一级只显示供应商名称、品牌图标和模型数量。 |
| [02-provider-models.png](02-provider-models.png) | 二级显示当前供应商模型、搜索、状态筛选、独立模型开关及添加入口。 |
| [03-provider-disabled.png](03-provider-disabled.png) | 停用供应商后保留各模型原来的开关设置。 |

最终图片对应[捕获日志](capture-instrumentation.log)和[结果、时间及 SHA-256](capture-results.json)。均由第三轮两项截图用例当次生成，已核对设备 mtime、字节数及哈希。

## 实际运行记录

| 运行 | 断言结果 | 图片结果 |
| --- | --- | --- |
| 第一次完整运行 | 4 项通过，156.026 秒 | Quickstep 无响应弹窗遮挡，不作为展示图片；[原始诊断](diagnostics/first-run/instrumentation-results.json)。 |
| 第二次完整运行 | 4 项通过，137.235 秒 | System UI 无响应弹窗遮挡，不作为展示图片；[原始诊断](diagnostics/second-run/instrumentation-results.json)。 |
| 第三次，仅截图用例 | 2 项通过，77.106 秒 | 主目录三张图片清晰无遮挡。 |

主目录的 [instrumentation.log](instrumentation.log) 和 [instrumentation-results.json](instrumentation-results.json) 保留第二次完整功能验证结果，不能将其旧图片校验和用于第三次图片。首两轮的五张原图分别保存在对应诊断目录，包括 Codex 回归的两张图。

完整四项验证覆盖供应商分组与返回导航、共享名称和上游 ID 搜索叠加启用筛选、独立模型开关、供应商关闭/恢复保留模型设置，以及 Codex「获取模型→选中→保存」成功流程。前三项只使用本地配置；Codex 回归使用模拟凭据及设备回环 MockWebServer，无真实账号请求。测试检查页面及回调配置更新，不覆盖 AppStore 跨重启持久化或编辑重命名。

第三次只运行 `ProviderModelsFlowTest#firstLevelShowsProvidersAndDetailContainsOnlyTheirModels` 与 `ProviderModelsFlowTest#providerMasterSwitchPreservesEveryIndividualModelPreference`，它们是完整四项的子集，不计为额外两个独立功能覆盖。

## 构建与环境

| 独立测试 APK | SHA-256 |
| --- | --- |
| `PathPilot-0.1.7-ui-capture.apk` | `4c32cd9155f029763a52487f8e4b79cc97d3bd1cbe964a36f1df82e43f72a6d7` |
| `PathPilot-0.1.7-ui-tests.apk` | `3fd8174307b31acda9a7e260ff875d09919db49acf73e5318f2c1d19685960af` |

Runner 为 `dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner`，fixture 仅位于 androidTest APK；正常交付包仍仅为 ARM64。

主机没有 KVM，使用一次启动、不读取快照的软件模拟器。安装后预编译 target/test；运行期间先后出现 Quickstep 与 System UI 弹窗，分别关闭一次。第三次运行前确认桌面恢复。三轮使用同一组 APK，没有修改源码、重装或重启模拟器。

结束后已清空 capture 与 androidTest 两个测试包的数据（均返回 `Success`），并停止本次模拟器；`adb devices` 已无设备，生产包数据未操作。主机上的 APK、AVD 及原始证据保留。
