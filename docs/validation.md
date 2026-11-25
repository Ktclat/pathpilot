# 0.1.9 验证记录

日期：2026-11-25（UTC）。本轮将用量整理为底部独立「统计」页，统一中文字体与指标样式，增加日期范围、趋势、供应商和模型汇总。[0.1.8 完整验证记录](validation-0.1.8.md)已归档。

**状态：本地构建、JVM 测试和 Lint 通过；设备 UI 检查与远端 CI 进行中。** 上一版本的测试与截图不作为本轮通过依据。

## 构建与自动测试

本轮已执行：

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.9-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.9-arm64.apk
```

| 检查 | 状态 |
| --- | --- |
| 核心 JVM 测试 | 98 项通过 |
| Android / 统计聚合 JVM 测试 | 44 项通过 |
| Android Lint | 0 错误、13 条既有警告 |
| 常规 APK 签名、ZIP、包名及 ARM64-only 检查 | 通过 |
| Actions 配置检查 | actionlint 通过 |
| 最终源码的 GitHub Actions | 待完成；运行链接待记录 |

版本配置为 `0.1.9` / versionCode 10，常规包名 `dev.pathpilot.app`，架构 `arm64-v8a`。交付副本路径为 `artifacts/PathPilot-0.1.9-arm64.apk`；SHA-256：`ebed1653875c174d82b799824a6ebdad602c0338317b2250ea20b25c68491e38`。截图使用独立模拟器包，不能代替 ARM64 交付包。

## 统计与字体检查

待验证以下行为：

- 共享页与独立统计页使用一致的中文字体、标题和指标字号；空值不再使用大号「未提供」。
- 今天、7 天、30 天和全部范围按手机本地时间统计；概览、趋势、供应商、模型与请求列表范围一致。
- 已知 Token、缺失用量与真实零值分别展示；缓存输入和推理输出不重复加入总量。
- 供应商及模型汇总可以进入对应请求，搜索、结果筛选、请求详情与清空行为正确。
- 无记录、部分用量、长模型名、深色主题及放大字体下的排版可读。

实际数据来源仍为保留的最近 1,000 条请求元数据。没有价格、首 Token 时间或订阅剩余额度数据时，不推算相应指标。参考范围与统计口径见 [统计页参考](statistics-reference.md)。

## Android 设备与 UI 验证

模拟器 instrumentation、实际运行截图和逐张 UI 审查均待完成。完成后记录设备配置、测试数量、耗时、原始 runner 日志、截图及 APK 校验和；此处不沿用上一版本的通过数量。

本轮是否完成真实账号联调、手机 VPN 出口或其他设备局域网联调，需按实际执行结果记录。

本轮原始 [Gradle 日志](../artifacts/validation/v0.1.9/gradle.log)、[JVM 结果](../artifacts/validation/v0.1.9/jvm-results.json)和 [Lint XML](../artifacts/validation/v0.1.9/lint-results-debug.xml)已保留。
