# MCP 当前能力与后续方向

PathPilot 提供远程 MCP 连接管理，以及手机本机和局域网的 Tools 共享网关。上下游协议固定为 **MCP 2025-11-25 / Streamable HTTP**。本文按当前源码描述行为；实际构建、模拟器及客户端联调结果见 [验证记录](validation.md)。

快捷添加的四项来源、凭据要求与核查结果见 [远程 MCP 预设](mcp-presets.md)。GitHub 配置及手机网关与远程服务的分工见 [在手机上使用 MCP](android-mcp.md)。

## 手机负责什么

PathPilot 同时是远程 MCP 的 client、连接手机的客户端眼中的 server：保存来源地址与凭据、读取工具目录、控制允许共享的工具、转发调用。客户端仍是 host，选择模型、决定何时调用工具，并把工具结果交回模型。

模型和工具使用两个入口：模型 API 使用 `/v1/chat/completions`、`/v1/responses`，MCP 使用 `/mcp/<connectionId>` 接收 JSON-RPC。OpenAI function tools 与 MCP 是不同协议；给手机添加 MCP 连接后，支持 MCP 的客户端需要另外配置工具地址。现有模型 API 不自动插入工具或追加模型轮次。[MCP Tools][tools] · [OpenAI function tools][openai-tools]

## 在页面中配置

1. 打开底部「MCP → 添加连接」，选择 Microsoft Learn、OpenAI Docs、Context7 或 GitHub。Learn 和 OpenAI Docs 可直接添加；Context7 填 API Key，GitHub 填 PAT。选择「自定义连接」时填写名称、完整 HTTP(S) MCP URL 及所需 Bearer 令牌；URL 保留实际路径，不添加 `/v1`。
2. 预设保存后自动进入连接并获取工具；自定义连接可点击「获取工具」。应用完成初始化和分页读取，显示实际服务名称、协议版本及工具列表。请求失败保留配置并显示错误，再次点击即可重试。
3. 使用「共享全部工具」，或关闭该选项后选择具体工具。共享全部会包含来源后续新增的工具；按工具选择则仅共享已选名称。连接也可单独启用或禁用。
4. 打开「MCP 共享」。它有独立前台服务、端口和 `sk-` 访问密钥，默认端口为 `8318`；模型共享默认仍为 `8317`，两个监听端口不能相同。
5. 在连接详情选择「本机」或「局域网」，复制该连接的完整地址与 MCP 访问密钥。本机使用 `127.0.0.1`；局域网按实际网卡选择 IP，地址列表跟随网络变化更新。

| 客户端配置 | 示例 |
| --- | --- |
| 模型 Base URL | `http://127.0.0.1:8317/v1`，使用模型共享密钥 |
| 本机 MCP URL | `http://127.0.0.1:8318/mcp/<connectionId>` |
| 局域网 MCP URL | `http://192.168.1.50:8318/mcp/<connectionId>`，IP 以手机页面为准 |
| MCP 认证 | `Authorization: Bearer <MCP共享密钥>` |
| MCP 传输与版本 | Streamable HTTP，2025-11-25 |

预设保存为普通连接，后续可通过统一编辑器修改；相同地址与令牌复用已有连接，不会重复添加。添加预设不会启动共享，仍由用户选择工具并控制服务开关。

每个远程连接拥有独立入口，保留上游工具名；当前没有把多个来源合成一个工具目录。获取工具和外网调用沿用「设置 → 上游代理」。入站 MCP 密钥只用于手机鉴权，上游请求使用该连接保存的令牌。

关闭 MCP 共享会释放其会话并取消活动调用；模型共享由自己的开关控制。修改 MCP 端口前需停止 MCP 服务；重置 MCP 密钥后，客户端需更新密钥并重新初始化。关闭连接或工具后，后续目录查询与调用都按当前启用集合处理。

## 当前协议实现

实现位于 [McpClient.kt](../core/src/main/kotlin/dev/pathpilot/core/mcp/McpClient.kt)、[McpGatewayServer.kt](../core/src/main/kotlin/dev/pathpilot/core/mcp/McpGatewayServer.kt) 和 [McpModels.kt](../core/src/main/kotlin/dev/pathpilot/core/mcp/McpModels.kt)。当前复用 OkHttp、现有代理配置和 NanoHTTPD，未引入官方 Kotlin MCP SDK。

| 环节 | 当前行为 |
| --- | --- |
| 能力 | 只声明 Tools；支持 `initialize`、`notifications/initialized`、`ping`、`tools/list`、`tools/call` 和取消通知 |
| 版本 | 初始化协商 2025-11-25；远程响应版本不一致时报错，后续远程请求携带 `MCP-Protocol-Version` |
| 响应 | 远程可返回 JSON 或 SSE，客户端读取匹配请求 ID 的响应；手机下游返回 JSON，不提供 GET 通知流，GET 返回 405 |
| Session | 每个下游 Session 配独立上游客户端／Session；下游初始化返回 `Mcp-Session-Id`，后续请求携带该值，DELETE 释放会话 |
| 分页 | 完整读取远程 `tools/list` 各页，再过滤允许共享的工具，一次返回下游目录，不发 `nextCursor` |
| 工具调用 | 保留上游结果对象及 JSON-RPC 错误；工具 `isError` 与 HTTP／协议错误区分 |
| 会话失效 | 上游返回 404 时结束对应本地会话，由客户端重新初始化；不自动重放可能已经执行的工具调用 |
| Origin | 原生客户端可不带 Origin；存在时只接受本机回环或实际网卡 IP、当前端口的 HTTP Origin，其他值返回 403 |
| 请求边界 | 下游需带 Content-Length，支持 1 字节至 2 MiB 的 JSON 请求；不接受压缩或 chunked 请求；远程单个 JSON 响应／SSE 事件有 8 MiB 上限 |
| 并发与回收 | 最多 32 个会话、8 个并发工具请求；空闲 30 分钟的会话在后续请求时回收 |

独立 GET 事件流、事件断点续传、跨来源目录聚合不是当前功能。Resources、Prompts、Roots、Sampling、Elicitation 不对客户端声明支持。接入的远程服务应提供 2025-11-25 Tools 与无认证或静态 Bearer；需要浏览器授权的 MCP OAuth 服务待后续适配。

HTTP 及 Session 行为依据 [2025-11-25 传输规范][http]、[生命周期][lifecycle]、[工具分页][pagination]。上面列出的资源上限是 PathPilot 当前实现选择，不是 MCP 规范的通用上限。

## 记录、设置与备份

MCP 页面显示最近调用，并可进入「调用记录」。本机保留最近 1,000 条调用元数据，停止共享或重开应用后仍可查看。支持按今天、7 天、30 天或全部保留记录查看，按调用时的连接名与工具名筛选，并汇总筛选后的调用、成功和失败次数。可单独清空记录。记录不含请求参数、返回正文或凭据，不计入模型请求数或 Token。

模型请求与工具请求分别由客户端发送时，手机没有可靠依据把它们自动归为同一个任务。工具返回文本只有在后续成为模型输入时，才由该次模型响应的实际 usage 计入 Token；不按工具文本长度估算模型用量。

主题和语言设置同时适用于 MCP 页面。连接 URL、令牌、启用状态、工具选择、MCP 端口及访问密钥存入应用加密配置，并纳入 `.ppbackup` 口令备份。备份还包含供应商、路由与应用偏好，Codex 登录状态可选；模型历史和 MCP 调用记录均不在备份内。

恢复前需停止模型与 MCP 共享。默认保留目标手机的模型／MCP 端口、两套共享密钥及上游代理，只有选择恢复连接设置时替换这些值；本地加密配置损坏时使用备份完整恢复。恢复不会启动共享。备份采用 AES-256-GCM 与 PBKDF2-HMAC-SHA256（210,000 次）派生密钥，格式由 PathPilot 维护，不能直接导入 Magpie 或 CC Switch 的备份。

## 后续方向

按实际客户端和工具服务需要继续增加：

- **在线服务发现**：当前已提供四项精选静态预设，下一步可接入 MCP Registry 搜索与更多来源。Registry 元数据、远程连接与 `.mcpb` 本地包的区别见 [官方 MCP 来源与快捷接入](mcp-catalog-research.md)。
- **真实服务兼容记录**：记录远程服务、客户端、协商版本和鉴权方式；覆盖长耗时、取消、分页及手机网络切换。当前测试结果只以验证记录为准。
- **工具趋势**：在现有工具记录与成功／失败汇总上，按实际需求增加独立趋势；保持与模型 Token 口径分离。
- **MCP OAuth**：需要授权的服务接入时，补受保护资源发现、授权服务器发现、浏览器 PKCE 授权和刷新，而不是把静态令牌输入称为 OAuth。[认证规范][auth]
- **2026-07-28 适配**：该版取消初始化握手和协议 Session，改用每次请求的版本／能力元数据及新 HTTP 头契约。需实现对应适配后再声明兼容，不能只改协议版本字符串。[版本协定][modern-version] · [新版 HTTP][modern-http]
- **更多工具来源**：stdio 服务需要运行它的进程环境；电脑已有服务可先由其所在机器提供 HTTP 入口。跨来源聚合、Resources／Prompts、手机自有工具及 Agent 执行流程按具体使用场景增加。

设置、备份及 MCP 页面组织参考 CC Switch 与 Magpie 的一手源码；固定版本、可复用行为及两代 MCP 协议差异见 [设置与 MCP 参考核查](settings-mcp-reference.md)。本文件描述已实现能力与后续工作，不替代 [验证记录](validation.md) 中的实际测试证据。

[openai-tools]: https://github.com/openai/openai-python/blob/abbd37925aa749ec35cd45cd94722e449f909388/src/openai/types/chat/chat_completion_function_tool_param.py
[http]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/basic/transports.mdx
[lifecycle]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/basic/lifecycle.mdx
[tools]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/server/tools.mdx
[pagination]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/server/utilities/pagination.mdx
[auth]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/basic/authorization.mdx
[modern-version]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/basic/versioning.mdx
[modern-http]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/basic/transports/streamable-http.mdx
