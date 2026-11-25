# 0.1.8 验证记录

日期：2026-11-25（UTC）。本轮增加实际 Token 用量观测、按模型统计、持久请求历史与详情，并更新 MCP 后续方案。[0.1.7 记录](validation-0.1.7.md)保留。

## 构建与自动测试

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.8-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.8-arm64.apk
```

| 检查 | 结果 |
| --- | --- |
| 核心 JVM 测试 | 98 通过；本轮新增 20 项 |
| Android / 聚合 JVM 测试 | 36 通过；本轮新增 7 项 |
| 合计 | **134 通过，0 失败，0 跳过** |
| Android Lint | **0 错误，13 警告**；新增一项 SQLite transaction 的 UseKtx 建议 |
| 常规 APK | 签名、ZIP、包名和 ARM64-only 校验通过 |
| Actions 配置 | actionlint 通过 |

首次 Lint 分析 UsageAnalytics.kt 时出现分析器 `Bad parent: KtFile` 崩溃；源码停止变动后重跑上述完整命令成功，未关闭任何 Lint 规则。

常规包为 `dev.pathpilot.app`，版本 `0.1.8` / versionCode 9，仅 `arm64-v8a`。本地副本 `artifacts/PathPilot-0.1.8-arm64.apk` 的 SHA-256：

```text
c5eb46139a983f309f5195b924a829411fc3cf3cfb3f7fdedbf58004516e83b6
```

常规 DEX 未包含本轮 UI/SQLite instrumentation 测试类及测试密钥。截图构建为独立包，不能代替 ARM64 交付包。

## 请求观测与聚合

新增真实回环 HTTP 回归覆盖 Chat JSON、Chat SSE、Responses SSE、Codex 非流式与流式、未取得用量、上游 HTTP 错误、HTTP 200 内失败/不完整事件、未知或停用模型、供应商停用、下游提前关闭、服务停止取消及回调唯一性。观察单元测试覆盖部分字段、缓存输入/推理输出、数值边界、多行与分块 SSE，以及超出 4 MiB 后转发字节不变。

用量只读取实际响应字段，不改客户端 include_usage。观察缓冲每份 JSON/单 SSE 事件最多 4 MiB，超出时该段用量可能未知，但继续转发全部响应。HTTP 状态与成功/失败/中断分开，不把 HTTP 200 自动视为完整成功。

聚合测试覆盖本地午夜和七个自然日、夏令时、缺失值与真实零、部分用量、供应商/共享模型/上游模型隔离、结果筛选与数值边界。模型行和请求详情使用相同的历史范围和模型三元组。

## Android 设备验证

已编写 3 项用量/请求页面 instrumentation 与 6 项 SQLite 存储 instrumentation。页面使用明确标注的离线元数据，不发真实模型请求；SQLite 使用独立临时目录。实际模拟器运行结果及原始截图待本轮完成后补充。

本轮未验证真实账号订阅剩余额度，也未实现按价格计费。MCP 为一手资料核查与下一阶段网关方案，未集成 MCP 依赖或运行其接口；见 [MCP 方案](mcp-roadmap.md)。
