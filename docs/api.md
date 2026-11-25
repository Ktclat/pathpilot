# 本机、局域网 API 与兼容范围

手机启动共享服务后监听 `0.0.0.0:<设置端口>`，默认 `8317`，同一手机和其他设备使用不同的连接地址：

| 客户端位置 | Base URL 示例 |
| --- | --- |
| 同一手机上的其他 App | `http://127.0.0.1:8317/v1` |
| 同一局域网的电脑、平板或其他手机 | `http://192.168.1.50:8317/v1`，替换为运行 PathPilot 的手机实际 IP |

`127.0.0.1` 始终指发起请求的设备自身；电脑填它会连接电脑，不会连接手机。`0.0.0.0` 是监听配置，不是供客户端复制的地址。使用明确的 `127.0.0.1` 可避免 `localhost` 被客户端解析到 IPv6 地址。

两个入口共用端口、访问密钥、路由和协议。API Key 都填写 App 生成的访问密钥，**本机访问也需要鉴权**；只有 `/health` 免鉴权。上游 API Key、Codex 访问令牌和刷新令牌不交给客户端。

本机入口不依赖局域网 IP；未连接 Wi-Fi 或没有可显示的 LAN 地址时，服务开启后仍可本机访问。请求上游服务仍需相应网络可达。当前一个开关控制整个服务，关闭后本机和局域网入口一起停止；选择本机地址并不把监听改成仅限本机。

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

「模型共享」总开关控制整个监听服务，状态包含正在开启/已开启/正在关闭/已关闭。启动过程中可取消，停止时先释放监听与锁，再允许重开。尚未配置模型时也可以开启服务；此时 `/health` 可响应，鉴权后的 `/v1/models` 返回空列表，页面显示「暂无可用模型」。

### 客户端协议与地址填写

这是 OpenAI 兼容模型 API 的部分接口。客户端选择 **Chat Completions** 或 **Responses**，再选择支持该接口的模型别名，供应商差异见下表。两种协议使用相同 Base URL，改变地址不会把一种协议转换成另一种。

| 客户端字段或请求方式 | 同机示例 |
| --- | --- |
| Base URL / `base_url`，客户端自动追加接口路径 | `http://127.0.0.1:8317/v1` |
| 仅服务器地址，客户端明确自动追加 `/v1` | `http://127.0.0.1:8317` |
| 完整 Chat Completions 请求地址 | `http://127.0.0.1:8317/v1/chat/completions`，使用 POST |
| 完整 Responses 请求地址 | `http://127.0.0.1:8317/v1/responses`，使用 POST |
| 模型列表请求地址 | `http://127.0.0.1:8317/v1/models`，使用 GET |

其他设备只需把示例中的 `127.0.0.1` 换成手机局域网 IP。不要把完整 `/chat/completions` 或 `/responses` 端点填进会自动追加路径的 Base URL 字段，也不要重复 `/v1`。具体字段名依客户端而异，以它最终请求的路径为准。

同手机的客户端需要支持自定义 HTTP 地址。若客户端明确报 `CLEARTEXT communication not permitted`，是该客户端的 Android 网络策略禁止 HTTP，需使用它提供的 HTTP/本地服务设置或支持此类地址的客户端；PathPilot 的网络设置不能改变另一个 App 的策略。当前提供上述 OpenAI 接口，没有 Anthropic Messages 或 Gemini 原生端点。

### 访问密钥

0.1.3 起新生成的共享密钥采用 `sk-` 加 64 个十六进制字符，随机部分为 32 字节（256 位）。客户端 API Key 字段填写完整密钥，由客户端发送 `Authorization: Bearer <密钥>`。

`sk-` 是常见格式约定，Bearer 鉴权协议并不要求这个前缀。支持自定义 Base URL、Bearer API Key 及下列接口的客户端可接入；仅接受官方地址或使用不同接口的客户端还需相应适配。

升级保留原有 `pp_` 密钥，现有客户端配置继续可用。需要改用新格式时，在「设置 → 重置访问密钥」操作，再更新其他设备的 Key。密钥匹配按完整字符串校验，不按前缀放行。

官方 OpenAI Python SDK 支持 [自定义 Base URL](https://github.com/openai/openai-python/blob/abbd37925aa749ec35cd45cd94722e449f909388/README.md#L966)，[鉴权实现](https://github.com/openai/openai-python/blob/abbd37925aa749ec35cd45cd94722e449f909388/src/openai/lib/_client_auth.py#L347)将所配置的 Key 放入 Bearer 头。已使用 SDK 3.58.0 对本地模拟端点验证新旧格式的模型列表、Chat Completions、Responses 请求，详见验证记录。客户端自己的 UI 格式校验与具体功能支持仍取决于该客户端。

## 接口

| 接口 | 行为 |
| --- | --- |
| `GET /health` | 无鉴权，仅返回 `{"status":"ok"}`，不包含账号或配置 |
| `GET /v1/models` | 本机与 LAN 都需要 `Authorization: Bearer <访问密钥>`；仅列出启用路由且供应商启用的模型别名 |
| `POST /v1/chat/completions` | 根据 `model` 别名选定供应商和上游模型；支持 JSON 与 SSE |
| `POST /v1/responses` | Codex 和提供 Responses 接口的自定义供应商；支持 JSON 与 SSE |

模型列表来自手机路由配置，不会在每次读取时测试上游账号、探测 Responses 能力或保证模型在线。请求中的 `model` 填共享别名，而不是供应商名称。

在 App「模型」页先选择供应商，再查看其共享模型。每个条目是一个共享名称到该供应商上游模型 ID 的映射，内部称为路由；它不等于该账号的全部上游目录。供应商和模型均启用时，该名称才会列入 `/v1/models`；停用供应商保留各模型开关设置，恢复后继续按原设置共享。

POST 必须使用 `Content-Type: application/json` 和 `Content-Length`。请求体上限 2 MiB；不接收 gzip 或 chunked 请求体。响应 SSE 使用分块传输。每台手机最多同时转发 8 个请求，超出返回 `429 router_busy`。连接超时 15 秒，上游读取空闲超时 120 秒。

路由按精确别名匹配，不隐式选择其他账号，也不对生成请求自动重试；这样避免失败重试产生重复输出或重复计费。关闭服务会取消活动上游调用。客户端断开连接时，响应流关闭并取消上游调用。局域网密钥更新后立即用于后续请求，已开始的请求不会被密钥轮换强制结束。

客户端后续请求应继续使用所配置的共享别名。直接透传的响应（DeepSeek、自定义供应商及 Codex Responses SSE）可能在 `model` 字段中返回上游模型 ID；首版不重写这些响应内容。Codex 聊天转换及非流式 Responses 会返回共享别名。不要从透传响应的 `model` 自动替换客户端配置。

## 供应商兼容矩阵

| 供应商 | Chat Completions | Responses | 工具调用 |
| --- | --- | --- | --- |
| DeepSeek | JSON/SSE 转发，替换模型名 | `400 unsupported_endpoint`，不向上游发送请求 | Chat 工具字段转发，能力取决于上游模型 |
| 自定义 OpenAI 兼容 | JSON/SSE 转发至 Base URL 后的 `/chat/completions` | JSON/SSE 转发至 `/responses`，上游必须实际提供该接口 | 对应接口的工具字段转发，能力取决于上游 |
| Codex 订阅 | Responses → Chat 的 JSON/SSE 桥接，支持范围见下文 | 固定官方 Codex backend；上游始终 `stream=true, store=false`，客户端要 JSON 时聚合完成事件，要 SSE 时透传 | Chat 桥接支持 function tools；Responses 保留工具字段，由上游决定支持范围 |

客户端用 `stream=true` 请求 SSE，省略或设为 `false` 请求 JSON。手机转发或转换工具调用，不执行客户端函数；函数结果由客户端在下一轮请求中返回。已接入 DeepSeek API 不表示它支持 Responses；选择「自定义兼容」也不意味着上游同时实现两种协议。

DeepSeek Base URL 可填 `https://api.deepseek.com/v1`；自定义供应商的 Base URL 应包含其 API 前缀，例如 `https://example.com/v1`。自定义来源支持 HTTP/HTTPS；无认证服务可以留空 API Key，此时不发送 Authorization 头。Codex 上游地址固定，不会把订阅凭据转发到自定义地址。转发不跟随重定向。

### Codex Chat Completions 桥接

实现了用户、系统/开发者与助手消息；文本和用户图片 URL；function tools、工具选择、工具调用历史及字符串工具结果；`reasoning_effort`；文本/工具调用 SSE 增量；可选 `stream_options.include_usage`。工具结果暂不接受文本数组。上游拒绝内容保留在 Chat JSON 的 `message.refusal` 或 SSE 的 `delta.refusal`，纯拒绝 JSON 的 `content` 为 `null`。助手历史使用 `output_text`，用户和开发者内容使用 `input_text`，依据[官方 Codex 请求类型](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/protocol/src/models.rs)。

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

`./gradlew :core:test` 包含经 `127.0.0.1` 连接真实网关与 MockWebServer 上游的端到端测试，覆盖本机鉴权、路由、流式转发、错误脱敏、密钥轮换、请求体上限、禁用供应商、重定向禁用、Codex JSON 桥接、客户端断开、认证刷新超时与并发限制；桥接单元测试覆盖工具历史、工具增量、拒绝内容和截断流。测试使用模拟上游；不替代真实 ChatGPT 订阅、DeepSeek 账号、同手机客户端或另一台 LAN 设备联调。本段列出测试范围，实际执行结果见验证记录。


## 请求记录与用量（0.1.8）

手机记录通过入口鉴权的 `POST /v1/chat/completions` 和 `POST /v1/responses`，包括请求校验失败、模型/供应商不可用、限流、上游错误及流中断。健康检查、模型列表、未通过鉴权的请求不计入模型用量。每次请求只记录一次，包含请求开始时间、供应商和模型映射快照、耗时、HTTP 状态、终态及固定错误码。

Token 来自上游 Chat Completions JSON/SSE、Responses JSON/SSE 或 Codex 响应的 `usage`。字段缺失保持未知；只有输入和输出都提供时才补算总数。缓存输入、推理输出分别是输入/输出明细，不再次计入总量。流式 Chat 来源通常需要客户端发送 `stream_options: {"include_usage": true}` 才返回用量；PathPilot 不擅自修改此选项。没有 usage 不影响请求转发，也不会产生估算账单或订阅配额。

旁路观察每份 JSON/单个 SSE 事件最多缓存 4 MiB，超过时跳过该段的用量解析，完整转发仍继续。HTTP 状态与终态分开：例如上游先发 HTTP 200，随后断流，记录显示 HTTP 200 与「中断」。正常转发字节及已有协议转换不因记录功能改变。

历史保存在本机 noBackupFilesDir 下的 SQLite，只保存上述元数据，不保存请求/响应正文或凭据。按开始时间保留最新 1,000 条，统计基于这些记录；可在「用量与请求 → 更多操作」清空。
