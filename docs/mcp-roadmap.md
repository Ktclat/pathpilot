# MCP 后续范围

**当前未实现 MCP。** PathPilot 目前提供模型 API；本文件是后续建议，不代表 APK 已能连接或提供 MCP 工具。核查日期：2026-11-24。

## 对当前使用方式的价值

用户主要在电脑上的 Codex 或其他客户端使用手机提供的模型。优先让支持 MCP 的客户端继续负责工具，PathPilot 负责模型路由。增加 MCP 有助于共享工具和数据访问能力，不会自动增加模型来源、订阅额度或模型接口兼容性。

| 角色 | 实际行为 | 建议 |
| --- | --- | --- |
| 手机作为 MCP server | 向局域网客户端提供手机或路由工具；客户端仍是 host，决定何时调用 | 有具体手机工具需求时优先，使用独立可选的 `/mcp` 入口 |
| 手机作为 MCP host/client | 手机连接远程 MCP server、管理工具凭据，并把工具接入模型执行流程 | 等确有集中管理工具、手机内任务执行需求再做；连接管理之外还需要模型与工具调用循环 |
| 现有模型 API 路由 | 转发模型请求及 function tools，由请求方处理工具调用 | 保持现有职责，不因配置 MCP 自动改变请求 |

MCP 使用 JSON-RPC，定义工具发现、`tools/list`、`tools/call` 等消息。[OpenAI function tools][openai-tools] 是模型请求中的工具定义和调用结果，两者不是同一种协议。host 可以在两者之间转换，但需要处理工具名、参数、调用 ID、结果和下一轮模型请求。

若在现有 `/v1/chat/completions` 或 `/v1/responses` 内自动加入 MCP 工具并代为执行，会改变客户端提交的 `tools`、`tool_choice`、对话历史与响应过程，还会增加模型轮次、延迟和用量。这是新增执行语义，不能作为透明转发的小改动塞进现有入口。

## 最小下一阶段

先选定一个实际要连接的客户端和一个确实需要由手机提供的工具。若启动技术验证，建议只做可选 MCP server：

1. 独立开关、`/mcp` 地址、可撤销的 MCP 访问令牌；与模型 API 并存。
2. 两个只读验证工具：`pathpilot_list_models` 返回已允许共享的模型别名；`pathpilot_get_status` 返回运行状态和请求计数。不返回上游密钥、账号令牌、提示词或完整配置。
3. 用目标客户端验证发现、列出工具、调用、错误和取消；停用 MCP 后工具请求失效，现有模型 API 继续可用。

这两个工具适合验证接入，单凭它们的收益有限。后续可按真实用途增加手机文件或设备能力；届时再落实对应 Android 权限和操作范围。没有具体工具需求时，继续使用客户端自身的 MCP 功能即可。

首阶段只实现 Tools。手机 Agent、工具目录和运行时包管理按后续实际需求另行评估。

## 传输与 Android 可行性

- **Streamable HTTP**：适合手机连接远程工具，也适合局域网设备连接手机工具。它有独立 MCP 端点和 JSON-RPC 请求，响应可以是 JSON 或请求对应的 SSE；现有模型 SSE 转发本身不构成 MCP 支持。[传输规范][http]
- **stdio**：标准方式是 client 启动同机子进程，通过 stdin/stdout 交换消息，不是其他设备可直接填写的网络地址。Android 可以集成专门打包并适配的工具，但桌面上的 `npx`、`uvx` 或 Python 命令不能直接当作手机已具备的运行环境。现有 stdio 工具可先运行在电脑上，由客户端直接连接；需要手机访问时，再由工具所在机器提供 HTTP 入口。[stdio 规范][stdio]
- **复用候选**：官方 Kotlin SDK 提供 client/server 和 Streamable HTTP/stdio 实现；README 声明 JVM、Native、JS、Wasm，并未承诺已验证 Android。其 Ktor 引擎需自行选择；Ktor OkHttp client 引擎支持 Android，但这不等于整个 SDK 或 server 引擎已在本 App 验证。集成前做依赖解析、D8/minSdk 26 和真机启停验证，不需要为了 MCP 改成 Termux。[SDK][sdk] · [Ktor 引擎][ktor]

### 规范与 SDK 版本不能混用

官方仓库当前标记的最新稳定规范为 **2026-07-28**，有独立正式 tag；本次依据该版文档，不使用 draft。[版本入口][versions]

该版已取消 `initialize` 会话握手、`Mcp-Session-Id` 和 HTTP GET 通知流，改为每请求版本/能力元数据、`server/discover` 和 POST 响应流；Roots、Sampling 已弃用，不建议新项目通过 sampling 另造模型入口。[版本变化][changes]

本次核实的 Kotlin SDK 最新稳定版 **0.16.0**，`LATEST_PROTOCOL_VERSION` 仍为 **2025-11-25**，client 仍执行初始化握手，server 会拒绝版本头为 2026-07-28 的请求。它提供部分新发现消息类型，不等于已经实现新生命周期。首个原型应锁定目标客户端与 SDK 实际共同支持的版本；可先验证 2025-11-25 兼容路径，但不能标称最新协议全兼容。需要连接仅支持 2026-07-28 的一方时，需升级到支持该版的实现或补充独立适配后联调。[SDK 版本常量][sdk-version] · [client][sdk-client] · [server 版本校验][sdk-server]

## 鉴权与操作确认

**局域网 server**：用户明确开启局域网 MCP 后才监听 LAN；只接受授予该 MCP 入口的令牌，模型访问密钥不自动获得手机工具权限。普通私有 LAN 可以使用独立静态 Bearer 令牌，前提是目标客户端支持配置该请求头；这不等同于实现 MCP OAuth 自动发现。沿用 HTTP 时传输仍是明文，需要保密传输则增加 TLS 或使用已有受信任 VPN。按规范检查请求中存在的 `Origin`，拒绝不允许的来源，同时保留令牌鉴权。[HTTP 要求][http] · [授权范围][auth]

**远程 client（后续）**：远程 MCP 凭据按服务分别存储，不把 Codex/DeepSeek 凭据或手机共享密钥拿去登录工具服务。若目标服务要求标准 OAuth，再实现资源/授权服务器发现、PKCE、面向目标 resource 的令牌和浏览器授权；无需为静态令牌服务提前建设整套 OAuth。MCP 入站令牌与访问第三方 API 的令牌保持分离。[授权安全要求][auth-security]

**工具授权**：在用户明确选择的工具、数据和操作范围内持续授权，不重复弹窗。删除文件、发送消息、修改配置等能力按已授予的范围执行；超出范围时再展示具体目标与参数取得授权。Android 权限按具体能力申请，第三方工具的 `readOnlyHint` 作为描述信息使用。客户端 host 负责交互，手机 server 执行实际权限检查。[工具规范][tools]

后续验收应覆盖：目标客户端的协议版本、JSON/SSE 两种响应、缺失/撤销令牌、非法 Origin、越权工具、停用时取消调用、拒绝操作不产生副作用，以及启用 MCP 不改写现有模型请求。本次仅文档研究，未集成 SDK、未运行 MCP 或 Android 兼容性测试。

[openai-tools]: https://github.com/openai/openai-python/blob/abbd37925aa749ec35cd45cd94722e449f909388/src/openai/types/chat/chat_completion_function_tool_param.py
[versions]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/docs.json#L33
[changes]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/changelog.mdx
[http]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/basic/transports/streamable-http.mdx
[stdio]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/basic/transports/stdio.mdx
[tools]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/server/tools.mdx
[auth]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/basic/authorization/index.mdx
[auth-security]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/basic/authorization/security-considerations.mdx
[sdk]: https://github.com/modelcontextprotocol/kotlin-sdk/tree/4c55826b6af00d616e1f5a285677ff53867cb941
[sdk-version]: https://github.com/modelcontextprotocol/kotlin-sdk/blob/4c55826b6af00d616e1f5a285677ff53867cb941/kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/common.kt
[sdk-client]: https://github.com/modelcontextprotocol/kotlin-sdk/blob/4c55826b6af00d616e1f5a285677ff53867cb941/kotlin-sdk-client/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/client/Client.kt
[sdk-server]: https://github.com/modelcontextprotocol/kotlin-sdk/blob/4c55826b6af00d616e1f5a285677ff53867cb941/kotlin-sdk-server/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/server/StreamableHttpServerTransport.kt#L803
[ktor]: https://github.com/ktorio/ktor-documentation/blob/2392849f91ec7aa55e269c2d5eb0de99889fe7cf/topics/client-engines.md
