# 0.1.6 验证记录

日期：2026-11-25（UTC）。本轮补全 Codex 订阅登录后的模型获取、选择及路由保存流程。[0.1.5 记录](validation-0.1.5.md)保留。

## 构建与自动测试

最终代码执行：

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.6-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.6-arm64.apk
```

| 检查 | 结果 |
| --- | --- |
| 核心 JVM 测试 | 78 通过；本轮新增 Codex 目录 12 项 |
| Android ViewModel / runtime JVM 测试 | 29 通过；本轮新增 Codex 模型获取 7 项 |
| 合计 | **107 通过，0 失败，0 跳过** |
| Android Lint | **0 错误，12 警告** |
| APK | ZIP、签名、ARM64 架构与包名检查通过 |
| Actions 配置 | actionlint 通过 |

Lint 为 11 条既有项和新增 androidTest MockWebServer 的版本提示；该库沿用与生产 OkHttp 一致的 4.12.0。常规 APK 不包含 instrumentation fixture Token、测试供应商 ID 或测试类。

常规包为 `dev.pathpilot.app`，版本 `0.1.6` / versionCode 7，仅 `arm64-v8a`。本地副本 `artifacts/PathPilot-0.1.6-arm64.apk` 的 SHA-256：

```text
3d5358c7f556930d718e606f6b2dd8dba51033299593778d491d72e688f27206
```

目录测试使用 MockWebServer，覆盖固定官方端点及版本、Bearer/账号头、订阅专属模型保留、隐藏项过滤、空目录、HTTP 错误、格式错误、代理、取消、超时、重定向和响应大小。官方固定目录实测 667,893 字节，包含大块模型说明；Codex 单独允许 4 MiB，并验证超过 1 MiB 的合成同形目录可解析。[一手接口依据](codex-research.md#订阅模型目录2026-11-25-补充)。

ViewModel 回归覆盖凭据解析与重试、选模后路由提交、取消与迟到结果、供应商隔离及代理切换；同一供应商重新登录会清除旧目录、选中项和筛选词，保留手动输入。普通重试保留用户选择。

## Android 界面离线联调

独立 `dev.pathpilot.app.capture` 测试包与 androidTest APK 已编译。使用生产 Compose 组件、ViewModel 和目录客户端，通过 MockWebServer 回环 HTTP 验证模型获取流程；登录状态和凭据由测试 fixture 提供，不进行真实 OAuth 或调用真实订阅目录。fixture 仅位于 androidTest APK，测试宿主只加入截图构建。

截图包 SHA-256：`19a502292bfea043af7de6f2db6087adfe6771f2ebfd6c45fcd8c981dacadae2`。
测试包 SHA-256：`3fa88dbf71154a5eb932ada7ddc85969a0f9564b8a34d931e1b4c0848f91d343`。

3 项 instrumentation 测试正在模拟器执行；结果与原始截图将补入 [截图目录](../artifacts/screenshots/v0.1.6/README.md)。测试流程包括已登录入口获取、选模保存，401 显示与重试，以及未登录时展示登录操作。路由保存检查的是组件回调调用 `ProviderRoutes.addSelected` 的配置结果，不代表本轮验证了 AppStore 跨重启持久化。

本轮未使用真实 ChatGPT 账号验证授权、目录或推理；界面与错误状态的截图会明确标记为离线联调。
