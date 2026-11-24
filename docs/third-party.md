# 依赖与参考

本项目原有许可证为 MIT，保留根目录 `LICENSE`。新增实现为独立 Kotlin/Compose 代码，没有直接移植 RikkaHub 或其他参考项目的源文件。

直接运行时依赖：

| 依赖 | 许可证 | 用途 |
| --- | --- | --- |
| Kotlin 标准库、kotlinx.coroutines、kotlinx.serialization | Apache-2.0 | 语言、异步和 JSON |
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 | 原生 UI 与生命周期 |
| OkHttp / Okio | Apache-2.0 | HTTPS、SSE 与网络流 |
| NanoHTTPD 2.3.1 | BSD-3-Clause | 手机内嵌 HTTP 服务 |
| LobeHub Icons 6.20.0 的 OpenAI、DeepSeek 矢量素材 | MIT | 供应商选择、列表和配置标题中的品牌标记 |

测试依赖 JUnit 4（EPL-1.0）和 MockWebServer（Apache-2.0）。构建及 APK 保留依赖包中可适用的许可证与声明；发布时应同时提供依赖许可证清单。

协议研究参考 OpenAI Codex 官方 Apache-2.0 源码；产品架构研究参考 MIT 的 CC Switch、CLIProxyAPI 及 AGPL-3.0 的 RikkaHub。固定版本与原始链接详见 `reuse-assessment.md` 和 `codex-research.md`。

## 供应商品牌素材

采用 [LobeHub Icons](https://github.com/lobehub/lobe-icons) 的固定提交 `05260484d9f4de68f90cc87a634ae68fa4043efd`，上游包版本为 6.20.0：

| 标记 | 固定来源 | Android 资源 |
| --- | --- | --- |
| OpenAI / ChatGPT | [openai.svg](https://github.com/lobehub/lobe-icons/blob/05260484d9f4de68f90cc87a634ae68fa4043efd/packages/static-svg/icons/openai.svg) | `ic_provider_openai.xml` |
| DeepSeek | [deepseek-color.svg](https://github.com/lobehub/lobe-icons/blob/05260484d9f4de68f90cc87a634ae68fa4043efd/packages/static-svg/icons/deepseek-color.svg) | `ic_provider_deepseek.xml` |

素材许可证为 [MIT](https://github.com/lobehub/lobe-icons/blob/05260484d9f4de68f90cc87a634ae68fa4043efd/LICENSE)，版权声明为 `Copyright (c) 2023 LobeHub`。原始 SVG、完整许可证与来源说明保存在 [`app/src/main/assets/third_party/lobehub-icons`](../app/src/main/assets/third_party/lobehub-icons)，并随 APK 分发。

Android VectorDrawable 保留原始路径、24×24 画布和填充规则，未重绘图形。OpenAI 单色标记使用界面前景色适配明暗模式；DeepSeek 保留素材中的 `#4D6BFE`。通用 OpenAI 兼容供应商仍使用中性服务器图标，不根据接口兼容性套用 OpenAI 品牌。标记仅用于识别对应供应商，商标归各自持有人所有。
