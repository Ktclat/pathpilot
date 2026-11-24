# 局域网 API 与兼容范围

手机启动共享后监听 `0.0.0.0:<设置端口>`，默认 `8317`。客户端的 Base URL 填 `http://<手机局域网 IP>:8317/v1`，API Key 填 App 生成的局域网访问密钥。上游 API Key、Codex 访问令牌和刷新令牌不交给客户端。

### 多个 IP 与共享开关

同一手机可以同时有 Wi-Fi、热点、网线、USB 共享或 VPN 地址。下面是说明用途的示例，实际值以手机接口为准：

| 手机地址 | 客户端应如何选择 |
| --- | --- |
| Wi-Fi `192.168.1.50` | 与手机连接同一路由器的电脑，使用此地址 |
| 热点 `192.168.43.1` | 连接手机热点的设备，使用手机在热点接口上的地址 |
| VPN `10.8.0.2` | 需客户端有通往该 VPN 地址的路由，单独展开查看 |

路由器的网关地址、其他设备的 IP 都不是手机的 API 入口，应用只枚举本机接口。当前监听和展示均为 IPv4。

0.1.4 首页优先显示 Wi-Fi/有线地址；多个候选通过地址菜单切换，并标明网络类型和接口名。系统未公开热点/USB 类型时显示「网络接口 · 接口名」，保留真实地址。VPN/蜂窝地址折叠在「其他网络地址」中。正在显示的地址消失时自动选择剩余地址；共享关闭时，前台页面同样每 5 秒刷新。

所有地址共用同一端口、访问密钥和模型路由。地址选择只影响显示与复制，不改变 `0.0.0.0` 监听或上游代理。手机使用 VPN 时，局域网互访还取决于 VPN 应用的局域网放行设置。

「局域网共享」总开关控制整个监听服务，状态包含正在开启/已开启/正在关闭/已关闭。启动过程中可取消，停止时先释放监听与锁，再允许重开。尚未配置模型时也可以开启服务；此时 `/health` 可响应，鉴权后的 `/v1/models` 返回空列表，页面显示「暂无可用模型」。

### 访问密钥

0.1.3 起新生成的共享密钥采用 `sk-` 加 64 个十六进制字符，随机部分为 32 字节（256 位）。客户端 API Key 字段填写完整密钥，由客户端发送 `Authorization: Bearer <密钥>`。

`sk-` 是常见格式约定，Bearer 鉴权协议并不要求这个前缀。支持自定义 Base URL、Bearer API Key 及下列接口的客户端可接入；仅接受官方地址或使用不同接口的客户端还需相应适配。

升级保留原有 `pp_` 密钥，现有客户端配置继续可用。需要改用新格式时，在「设置 → 重置访问密钥」操作，再更新其他设备的 Key。密钥匹配按完整字符串校验，不按前缀放行。

官方 OpenAI Python SDK 支持 [自定义 Base URL](https://github.com/openai/openai-python/blob/abbd37925aa749ec35cd45cd94722e449f909388/README.md#L966)，[鉴权实现](https://github.com/openai/openai-python/blob/abbd37925aa749ec35cd45cd94722e449f909388/src/openai/lib/_client_auth.py#L347)将所配置的 Key 放入 Bearer 头。已使用 SDK 3.58.0 对本地模拟端点验证新旧格式的模型列表、Chat Completions、Responses 请求，详见验证记录。客户端自己的 UI 格式校验与具体功能支持仍取决于该客户端。

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

DeepSeek Base URL 可填 `https://api.deepseek.com/v1`；自定义供应商的 Base URL 应包含其 API 前缀，例如 `https://example.com/v1`。自定义来源支持 HTTP/HTTPS；无认证服务可以留空 API Key，此时不发送 Authorization 头。Codex 上游地址固定，不会把订阅凭据转发到自定义地址。转发不跟随重定向。

### Codex Chat Completions 桥接

实现了用户、系统/开发者与助手消息；文本和用户图片 URL；function tools、工具选择、工具调用历史及工具结果；`reasoning_effort`；文本/工具调用 SSE 增量；可选 `stream_options.include_usage`。助手历史使用 `output_text`，用户和开发者内容使用 `input_text`，依据[官方 Codex 请求类型](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/protocol/src/models.rs)。

以下字段在 Chat 桥接中显式拒绝：`n` 非 1、`temperature`、`top_p`、`max_tokens`、`max_completion_tokens`、`stop`、`presence_penalty`、`frequency_penalty`、`logit_bias`、`audio`、`modalities`、`logprobs`、`top_logprobs`、`response_format`、`seed`。客户端应关闭这些额外参数，或使用原生 Responses 接口发送经供应商支持的字段。

不实现 `/v1/responses/{id}`、WebSocket、后台任务、文件上传、音频、嵌入或批处理。Codex 请求必须携带完整对话历史；`previous_response_id` 和 `background=true` 显式拒绝。无法根据已有 HTTP 测试宣称所有 Codex CLI 版本或所有第三方客户端均兼容；具体客户端仍需联调。

## 错误与隐私

未鉴权返回 401；未知别名返回 404；供应商关闭或 DeepSeek 缺少 API Key 返回 503；连接失败、Codex 未登录或凭据解析失败返回 502；超时返回 504。上游非成功 HTTP 状态保留，但错误正文替换为不含凭据的统一错误，数字 `Retry-After` 可透传。日志事件仅保留别名、供应商 ID、接口、状态和耗时，不记录提示词、回答或密钥。

目前 LAN 入口使用 HTTP，局域网密钥与内容在网络上传输时不加密。因此应在可信私人 Wi-Fi 使用，不要做公网端口映射；需要跨不可信网络时应先通过受信任 VPN/TLS 入口连接。手机到官方上游使用 HTTPS 且保留证书验证。

## 上游代理

在「设置 → 上游代理」选择跟随系统、HTTP 或 SOCKS5。显式代理填写主机/IP和端口，当前用于无需用户名密码的代理。配置在后续请求生效，已经开始的请求继续使用原连接。

代理覆盖 App 内模型请求、模型列表、Codex 设备授权请求和令牌刷新。跟随系统使用平台 ProxySelector 与普通 socket，手机 VPN/TUN 仍可按应用/域名接管；显式代理也不绕过 Android VPN。HTTP CONNECT 和 SOCKS5 隧道内的 HTTPS 继续验证上游证书。代理连接失败不会偷偷回退直连。

浏览器中的 OpenAI 登录网页使用浏览器自己的网络设置。代理配置不会更改局域网监听地址或给其他设备提供通用代理服务。

## 本地验证证据

`./gradlew :core:test` 包含真实 localhost 网关与 MockWebServer 上游的端到端测试，覆盖鉴权、路由、流式转发、错误脱敏、密钥轮换、请求体上限、禁用供应商、重定向禁用、Codex JSON 桥接、工具增量转换、截断流、客户端断开、认证刷新超时与并发限制。测试使用模拟上游；不替代真实 ChatGPT 订阅、DeepSeek 账号或 Android 真机联调。
