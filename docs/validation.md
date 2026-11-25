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

常规包为 `dev.pathpilot.app`，版本 `0.1.7` / versionCode 8，仅 `arm64-v8a`。本地副本 `artifacts/PathPilot-0.1.7-arm64.apk` 的 SHA-256：

```text
a7f16aa02af9966675e37a09552c62bc752bf8822f0a9063d588e2e7dc44d6f6
```

常规 APK 检查未包含本轮 fixture 密钥及 instrumentation 测试类。截图包和测试包独立构建，不能代替常规交付包。

本轮只调整模型管理界面、导航和文案，沿用原有 `RouteConfig`、共享模型名、配置存储及转发逻辑。模型开关只更新对应模型；供应商开关只更新供应商，保留每个模型的设置。客户端可见模型仍须同时满足供应商与模型已启用。

## Android 界面验证

使用 API 35 软件模拟器运行生产 `ModelRoutesPage` 及其两级页面。测试宿主提供两个明确标注「离线测试」的供应商及四个本地模型配置，不使用真实账号。

待本轮运行完成后补充四项 instrumentation 的实际结果及原始截图：供应商分组与返回导航、搜索与筛选及模型开关、供应商开关保留单模型配置，以及既有 Codex 获取模型成功流程回归。前三项仅使用本地配置；Codex 回归使用模拟凭据和 MockWebServer。

本轮界面测试不覆盖 AppStore 跨重启持久化、编辑重命名或真实订阅请求。这些范围不计入本轮通过结果。
