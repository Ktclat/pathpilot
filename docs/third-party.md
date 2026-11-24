# 依赖与参考

本项目原有许可证为 MIT，保留根目录 `LICENSE`。新增实现为独立 Kotlin/Compose 代码，没有直接移植 RikkaHub 或其他参考项目的源文件。

直接运行时依赖：

| 依赖 | 许可证 | 用途 |
| --- | --- | --- |
| Kotlin 标准库、kotlinx.coroutines、kotlinx.serialization | Apache-2.0 | 语言、异步和 JSON |
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 | 原生 UI 与生命周期 |
| OkHttp / Okio | Apache-2.0 | HTTPS、SSE 与网络流 |
| NanoHTTPD 2.3.1 | BSD-3-Clause | 手机内嵌 HTTP 服务 |

测试依赖 JUnit 4（EPL-1.0）和 MockWebServer（Apache-2.0）。构建及 APK 保留依赖包中可适用的许可证与声明；发布时应同时提供依赖许可证清单。

协议研究参考 OpenAI Codex 官方 Apache-2.0 源码；产品架构研究参考 MIT 的 CC Switch、CLIProxyAPI 及 AGPL-3.0 的 RikkaHub。固定版本与原始链接详见 `reuse-assessment.md` 和 `codex-research.md`。
