# 0.1.7 验证记录

日期：2026-11-25（UTC）。本轮把模型管理改为「供应商 → 具体模型」两级结构，增加供应商内搜索、状态筛选及供应商开关。[0.1.6 记录](validation-0.1.6.md)保留。

## 构建与自动测试

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.7-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.7-arm64.apk
```

| 检查 | 结果 |
| --- | --- |
| 核心 JVM 测试 | 78 通过 |
| Android ViewModel / runtime JVM 测试 | 29 通过 |
| 合计 | **107 通过，0 失败，0 跳过** |
| Android Lint | **0 错误，12 条既有警告** |
| APK | ZIP、签名、ARM64 架构与包名检查通过 |
| Actions 配置 | actionlint 通过 |

提交 `158e05d` 的 [GitHub Actions](https://github.com/Ktclat/pathpilot/actions/runs/45526870305) 已成功，耗时 **2 分 33 秒**；测试、构建、Lint、ARM64 校验及产物上传均通过。

常规包为 `dev.pathpilot.app`，版本 `0.1.7` / versionCode 8，仅 `arm64-v8a`。本地副本 `artifacts/PathPilot-0.1.7-arm64.apk` 的 SHA-256：

```text
a7f16aa02af9966675e37a09552c62bc752bf8822f0a9063d588e2e7dc44d6f6
```

常规 APK 检查未包含本轮 fixture 密钥及 instrumentation 测试类。截图包和测试包独立构建，不能代替常规交付包。

本轮只调整模型管理界面、导航和文案，沿用原有 `RouteConfig`、共享模型名、配置存储及转发逻辑。模型开关只更新对应模型；供应商开关只更新供应商，保留每个模型的设置。客户端可见模型仍须同时满足供应商与模型已启用。

## Android 界面验证

使用 API 35 软件模拟器运行生产 `ModelRoutesPage` 及其两级页面。测试宿主提供两个明确标注「离线测试」的供应商及四个本地模型配置，不使用真实账号。

最终完整 instrumentation 运行结果为 **4 项通过，0 失败**，JUnit 耗时 **137.235 秒**。逐例均返回 `INSTRUMENTATION_STATUS_CODE: 0`：

- 一级只列供应商；打开后只显示该供应商模型，返回恢复供应商列表。
- 搜索匹配共享模型名与上游 ID，可叠加启用状态筛选；单模型开关不改变其他供应商或模型。
- 关闭、恢复供应商总开关均保留每个模型的设置。
- Codex 供应商「获取模型」成功读取模拟目录，选中后保存对应共享模型配置。

前三项仅使用本地配置；Codex 回归使用模拟凭据和 MockWebServer。截图包 SHA-256 为 `4c32cd9155f029763a52487f8e4b79cc97d3bd1cbe964a36f1df82e43f72a6d7`，测试包为 `3fd8174307b31acda9a7e260ff875d09919db49acf73e5318f2c1d19685960af`。

四项功能验证的原始 [instrumentation 日志](../artifacts/screenshots/v0.1.7/instrumentation.log)及[结果](../artifacts/screenshots/v0.1.7/instrumentation-results.json)已保留。

两次完整运行的四项断言均通过（156.026 秒、137.235 秒），截图却分别被模拟器 Quickstep、System UI 无响应弹窗遮挡，均未作为有效界面截图。分别保留[首轮原始记录](../artifacts/screenshots/v0.1.7/diagnostics/first-run/instrumentation-results.json)和[第二轮记录](../artifacts/screenshots/v0.1.7/diagnostics/second-run/instrumentation-results.json)。没有修改源码、重新安装或重启模拟器。

关闭 System UI 弹窗并确认桌面恢复后，仅复跑生成分组页、模型页及停用状态截图的两项用例，**2 项通过，耗时 77.106 秒**。这两项是上述四项中的子集。三张原始 PNG 已逐张检查，界面清晰且没有弹窗遮挡；该轮[捕获日志](../artifacts/screenshots/v0.1.7/capture-instrumentation.log)、[结果与校验和](../artifacts/screenshots/v0.1.7/capture-results.json)及图片见[截图目录](../artifacts/screenshots/v0.1.7/README.md)。

本轮界面测试不覆盖 AppStore 跨重启持久化、编辑重命名或真实订阅请求。这些范围不计入本轮通过结果。
