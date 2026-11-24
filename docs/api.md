# 局域网 API 与兼容范围

手机启动共享后监听 `0.0.0.0:<设置端口>`，默认 `8317`。客户端的 Base URL 填 `http://<手机局域网 IP>:8317/v1`，API Key 填 App 生成的局域网访问密钥。上游 API Key、Codex 访问令牌和刷新令牌不交给客户端。

## 接口

| 接口 | 行为 |
| --- | --- |
| `GET /health` | 无鉴权，仅返回 `{"status":"ok"}`，不包含账号或配置 |
| `GET /v1/models` | 需要 `Authorization: Bearer <局域网密钥>`；仅列出启用路由且供应商启用的模型别名 |
| `POST /v1/chat/completions` | 根据 `model` 别名选定供应商和上游模型；支持 JSON 与 SSE |
| `POST /v1/responses` | Codex 和提供 Responses 接口的自定义供应商；支持 JSON 与 SSE |

POST 必须使用 `Content-Type: application/json` 和 `Content-Length`。请求体上限 2 MiB；不接收 gzip 或 chunked 请求体。响应 SSE 使用分块传输。每台手机最多同时转发 8 个请求，超出返回 `429 router_busy`。连接超时 15 秒，上游读取空闲超时 120 秒。

路由按精确别名匹配，不隐式选择其他账号，也不对生成请求自动重试；这样避免失败重试产生重复输出或重复计费。关闭服务会取消活动上游调用。客户端断开连接时，响应流关闭并取消上游调用。局域网密钥更新后立即用于后续请求，已开始的请求不会被密钥轮换强制结束。

客户端后续请求应继续使用所配置的共享别名。直接透传的响应（DeepSeek、自定义供应商及 Codex Responses SSE）可能在 `model` 字段中返回上游模型 ID；首版不重写这些响应内容。Codex 聊天转换及非流式 Responses 会返回共享别名。不要从透传响应的 `model` 自动替换客户端配置。

## 供应商兼容矩阵

| 供应商 | Chat Completions | Responses |
| --- | --- | --- |
| DeepSeek | 原样转发，替换模型名；文本、工具和流式能力由 DeepSeek 模型决定 | 返回明确的 `400 unsupported_endpoint`，没有伪装成完整 Responses |
| 自定义 OpenAI 兼容 | 转发至供应商 Base URL 后的 `/chat/completions` | 转发至 `/responses`；是否支持由供应商决定 |
| Codex 订阅 | 转换为 Codex Responses 请求，并将结果转换回 Chat Completions | 固定转发至官方 Codex backend；始终 `stream=true, store=false`，客户端要求 JSON 时收集完成事件 |

DeepSeek Base URL 可填 `https://api.deepseek.com/v1`；自定义供应商的 Base URL 应包含其 API 前缀，例如 `https://example.com/v1`。Codex 上游地址固定，不会把订阅凭据转发到自定义地址。转发不跟随重定向。

### Codex Chat Completions 桥接

实现了用户、系统/开发者与助手消息；文本和用户图片 URL；function tools、工具选择、工具调用历史及工具结果；`reasoning_effort`；文本/工具调用 SSE 增量；可选 `stream_options.include_usage`。助手历史使用 `output_text`，用户和开发者内容使用 `input_text`，依据[官方 Codex 请求类型](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/protocol/src/models.rs)。

以下字段在 Chat 桥接中显式拒绝：`n` 非 1、`temperature`、`top_p`、`max_tokens`、`max_completion_tokens`、`stop`、`presence_penalty`、`frequency_penalty`、`logit_bias`、`audio`、`modalities`、`logprobs`、`top_logprobs`、`response_format`、`seed`。客户端应关闭这些额外参数，或使用原生 Responses 接口发送经供应商支持的字段。

不实现 `/v1/responses/{id}`、WebSocket、后台任务、文件上传、音频、嵌入或批处理。Codex 请求必须携带完整对话历史；`previous_response_id` 和 `background=true` 显式拒绝。无法根据已有 HTTP 测试宣称所有 Codex CLI 版本或所有第三方客户端均兼容；具体客户端仍需联调。

## 错误与隐私

未鉴权返回 401；未知别名返回 404；供应商关闭或缺少 API Key 返回 503；连接失败、Codex 未登录或凭据解析失败返回 502；超时返回 504。上游非成功 HTTP 状态保留，但错误正文替换为不含凭据的统一错误，数字 `Retry-After` 可透传。日志事件仅保留别名、供应商 ID、接口、状态和耗时，不记录提示词、回答或密钥。

目前 LAN 入口使用 HTTP，局域网密钥与内容在网络上传输时不加密。因此应在可信私人 Wi-Fi 使用，不要做公网端口映射；需要跨不可信网络时应先通过受信任 VPN/TLS 入口连接。手机到官方上游使用 HTTPS 且保留证书验证。

## 本地验证证据

`./gradlew :core:test` 包含真实 localhost 网关与 MockWebServer 上游的端到端测试，覆盖鉴权、路由、流式转发、错误脱敏、密钥轮换、请求体上限、禁用供应商、重定向禁用、Codex JSON 桥接、工具增量转换、截断流、客户端断开、认证刷新超时与并发限制。测试使用模拟上游；不替代真实 ChatGPT 订阅、DeepSeek 账号或 Android 真机联调。
