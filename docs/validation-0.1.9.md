# 0.1.9 验证记录

日期：2026-11-25（UTC）。本轮增加独立「统计」页，统一字体与指标样式，补充日期范围、趋势和供应商/模型汇总。[0.1.8 验证记录](validation-0.1.8.md)已归档。

**本地构建、142 项 JVM 测试、8 个独立设备用例、截图审查及最终源码 CI 全部通过。** 设备用例分批执行，首轮大字体失败及修正后的通过记录均保留。

## 构建与自动测试

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.9-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.9-arm64.apk
```

| 检查 | 结果 |
| --- | --- |
| 核心 JVM 测试 | 98 项通过 |
| Android / 统计聚合 JVM 测试 | 44 项通过 |
| Android Lint | 0 错误、13 条既有警告 |
| 常规 APK 签名、ZIP、包名及 ARM64-only 检查 | 通过 |
| Actions 配置检查 | actionlint 通过 |
| 首轮源码 `8f328a0` | [Actions 通过](https://github.com/Ktclat/pathpilot/actions/runs/45553382277) |
| 布局修正 `5fcc65c` | [Actions 通过](https://github.com/Ktclat/pathpilot/actions/runs/45555631259) |
| 最终源码 `713b151` | [Actions 通过](https://github.com/Ktclat/pathpilot/actions/runs/45556519601) |

交付包为 `artifacts/PathPilot-0.1.9-arm64.apk`，版本 `0.1.9` / versionCode 10，包名 `dev.pathpilot.app`，仅含 `arm64-v8a`。SHA-256：`83dbaae6c2293bd92ab5e3bd2e7234617e4e3e1d93c22422130d7fbccd4ffd3e`。这是常规 debug APK，独立截图包不作为交付包。

[最终 Gradle 日志](../artifacts/validation/v0.1.9/gradle-restoration.log)、[JVM 结果](../artifacts/validation/v0.1.9/jvm-results.json)及 [Lint XML](../artifacts/validation/v0.1.9/lint-results-debug.xml)已保留。首轮和布局修正版的构建日志也保存在同目录。

## 统计口径与行为

- 今天、7 天、30 天和全部按手机本地时间统计。聚合测试覆盖自然日边界、夏令时重复/缺失小时、长历史合并时间桶，以及供应商和模型三元组隔离。
- 请求数、成功率、平均请求总耗时及 Token 基于保留的最近 1,000 条元数据；概览、趋势、供应商/模型汇总和下钻请求使用同一范围。
- 未取得的 Token 保持未知，真实零值仍为 0；部分总 Token 覆盖单独计数。缓存输入属于输入、推理输出属于输出，两者不重复计入总量。没有价格、首 Token 时间或订阅剩余额度数据，不生成相应数字。
- 空值使用「—」，指标沿用同一字体角色；大字体改单列。地址选项简为「本机 / 局域网」并保持等高，导航标签使用完整项宽且居中。
- 搜索、结果筛选、清空确认、HTTP 200 与中断结果的区分，以及模型/供应商下钻均有设备通过记录。
- 趋势恢复旧选中项时，以新范围中的有效索引显示和切换。新增回归真实保存索引 5，再在 composition 销毁时将六桶改为一桶，验证恢复后的请求/Token 读数及按钮状态。

结构参考与统计口径详见 [CC Switch 源码研究](statistics-reference.md)。

## Android 与 UI 验证

Android 15 / API 35，无 KVM 的软件模拟器；正常 720×1280、density 320（360dp）、font_scale 1.0；大字体 density 360（320dp）、font_scale 1.5。全部正式截图由 Android `UiAutomation.takeScreenshot()` 生成，未编辑像素。

| 批次 | 结果 | 范围与源码 |
| --- | --- | --- |
| `normal-attempt1` | 6 项通过，325.942 秒 | `8f328a0`：统计组件 5 项 + 完整导航 1 项 |
| `large-attempt1` | 1 项失败，52.588 秒 | 导航 `didOverflowWidth` 断言，未计为通过；实际地址按钮高低不齐另由原图发现 |
| `navigation-final` | 1 项通过，79.968 秒 | `5fcc65c`：修改布局后的完整导航、共享/统计空态及主页面 |
| `large-attempt2` | 1 项通过，110.171 秒 | `5fcc65c`：五个标签横纵向无溢出、地址按钮等高、指标/趋势/长模型；未跳过 |
| `restoration-final` | 1 项通过，35.342 秒 | `713b151`：图表真实保存/恢复，修正不改变可见布局 |

合计 **8 个独立用例**具有通过证据：统计组件 5 个、完整导航 1 个、大字体 1 个、恢复 1 个。重复导航执行不计入独立用例数，各批源码与 APK 来源分别记录。

[完整截图、各批次日志、结果与 APK SHA](../artifacts/screenshots/v0.1.9/README.md) · [逐步 UI 审查](statistics-ui-review.md)。主页面、空态、请求详情和大小字体图均已实际查看；大字体趋势图的轴线/读数位于该截图视口外，证据范围在 UI 审查中单列。

首轮导航断言与截图视觉表现不同：标签肉眼完整，未将其描述成已观察到的截字。调整为完整项宽后保持严格断言，第二轮通过。共享地址分段的高低错位则由实际截图确认并修复。启动准备期间的 Quickstep / System UI 弹窗均已保存诊断图，正式截图无遮挡。

测试采用明确标注的离线请求元数据；本轮没有重新进行真实订阅账号、手机 VPN 出口、其他局域网设备或实体 ARM64 手机联调，也未执行完整 TalkBack / 逐页深色模式验收。存储实现未改动，上一轮 SQLite 设备测试记录保留在 0.1.8 文档。

完成后已恢复模拟器原始字体与密度、清空两个隔离测试包数据并停止本次 VM。[清理结果](../artifacts/screenshots/v0.1.9/diagnostics/startup/cleanup.json)已记录。
