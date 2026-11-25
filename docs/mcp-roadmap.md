# MCP 后续方案

**当前 APK 尚未实现 MCP。** 本文是 2026-11-25 的接口核查与实施方案。本轮先完善模型用量和请求记录；下一阶段建议做「远程 MCP 连接管理 + 本机/局域网共享」，由现有客户端继续负责模型与工具的调用流程。

## 哪种方式适合 PathPilot

| 方式 | 手机负责什么 | 适用阶段 |
| --- | --- | --- |
| 客户端直接连接 MCP | PathPilot 只转发模型请求；电脑或手机上的客户端连接工具 | 现在即可这样组合使用，客户端本身需支持 MCP |
| 手机作为 MCP 网关 | 统一保存远程工具地址和凭据，向本机/局域网客户端提供 MCP 入口 | **建议的下一阶段**；最贴近现在统一管理和共享模型的用途 |
| 手机提供自己的 MCP 工具 | 向客户端提供模型状态、用量查询或用户选择的手机数据 | 网关贯通后按实际工具需求增加 |
| 手机作为 MCP host / Agent | 选择工具、执行工具、再请求模型，维护多轮任务 | 手机内任务执行功能成熟后单独建设 |

推荐的网关同时是远程 MCP 的 client、局域网客户端眼中的 server。客户端仍是 host，决定何时调用工具。仅保存 MCP 连接而没有共享入口或执行方，不能让现有模型自动使用这些工具。

模型 API 与 MCP 保持两个入口：`/v1/chat/completions`、`/v1/responses` 接收模型请求；MCP 接收 JSON-RPC 的 `tools/list`、`tools/call` 等消息。OpenAI 的 function tools 与 MCP 不是同一协议；host 在工具定义、调用 ID、参数和结果之间转换。[工具规范][tools] · [OpenAI function tools][openai-tools]

因此，首版不在每个模型旁增加一个含义不清的「启用 MCP」开关。工具由客户端配置；未来若增加手机 Agent，再在任务配置中选择模型和工具集合。现有模型 API 不自动插入工具，也不替客户端追加模型轮次。

## 下一阶段的最小交付

先打通**一个远程 Streamable HTTP 来源 → 手机 → 一个支持自定义 MCP 地址的客户端**，随后沿用相同连接结构增加来源。

1. 在「设置 → MCP」添加连接：名称、完整 MCP URL、认证方式（无认证 / Bearer）、凭据。提供「连接测试」「获取工具」；错误显示实际 HTTP / MCP 结果。
2. 连接详情显示工具名称、简短说明及允许共享的开关。首版只声明 Tools 能力；展示列表与实际调用使用同一授权集合。
3. 每个连接提供独立 `/mcp/<connectionId>` 入口，保持上游工具名。暂不合并为一个跨来源工具目录，避免名称冲突和重命名兼容问题。
4. 「共享」页在已配置 MCP 后显示独立「MCP 共享」开关、地址和访问密钥。复用已有「本机应用 / 局域网设备」及网卡选择；原型采用独立端口，例如 `8318`，不改动现有模型 API 的 `8317` 监听。
5. 客户端配置两个地址：模型请求使用原来的 `/v1`，工具连接使用指定的 MCP URL。手机关闭 MCP 时取消其活动调用，模型共享继续按自身开关运行。

建议先选一个支持静态 Bearer 的远程工具服务和一个能填写自定义请求头的客户端贯通。需要 OAuth 的服务接入时，再补资源/授权服务器发现、PKCE、浏览器授权及令牌刷新；不把静态令牌原型称为完整 MCP OAuth 实现。

外网 MCP 请求沿用当前上游代理设置；来源凭据按连接加密保存。入站 MCP 密钥与模型共享密钥分开，手机验证入站密钥后使用对应来源的上游凭据，不把下游密钥转发给远程服务。监听局域网时实现规范要求的 `Origin` 校验。沿用明文 HTTP 的客户端接入说明，与现有模型共享保持一致。[HTTP 传输][http] · [授权绑定][auth-security]

工具参数和结果默认不写入请求日志。若以后加入文件、设备或其他可修改数据的工具，再按具体能力落实 Android 权限、可访问范围与已有持续授权；工具的 `readOnlyHint` 只作为描述信息使用。

## 用量和最近请求如何衔接

| 记录类型 | 展示维度 | 统计口径 |
| --- | --- | --- |
| 模型请求 | 供应商、共享模型名、上游模型 ID、时间、结果、耗时、Token | 按实际模型响应的 usage 计入；未返回的字段保持未知 |
| MCP 工具调用 | MCP 连接、工具名、时间、结果、耗时 | 单独统计调用次数、失败数和耗时；一次工具调用不等于一次模型请求 |
| 手机 Agent 任务（后续） | 一项任务关联的模型请求与工具调用 | 用任务 ID 关联，模型 Token 汇总其实际模型请求，避免重复相加 |

MCP 工具本身返回的普通文本不是模型 Token 用量；它可能被客户端放进下一次模型输入，此时由下一次模型响应的 usage 体现。工具服务自己的费用或配额，也不以模型 Token 估算。

当客户端分别调用模型 API 与 MCP，手机通常没有足够信息自动推断它们属于同一个任务。首版在请求记录中分「模型 / 工具」查看；只有客户端显式传递可核对的关联标识，或以后由手机 host 管理任务时，才展示一条完整执行链。

网关完成后，可增加 PathPilot 自有的只读工具，例如 `pathpilot_list_models`、`pathpilot_get_usage`（指定时间范围和模型）及 `pathpilot_get_status`。它们复用本地统计查询，只返回聚合数据和运行状态，不需要再次调用模型。作为便利功能即可，不将这几个状态查询工具当作整个 MCP 方案的主要价值。

## 已核查的协议与复用候选

- 官方版本入口当前标记最新稳定规范为 **2026-07-28**；正式 tag 存在。[版本入口][versions] · [正式 tag][spec-tag]
- 该版移除 `initialize` 握手、`Mcp-Session-Id` 和 GET 通知流，使用请求版本/能力元数据、`server/discover` 与 POST 响应流；Roots、Sampling 已弃用。新实现不通过 Sampling 另造模型路由入口。[版本变化][changes]
- 官方 Kotlin SDK 最新稳定发布仍是 **0.16.0**，协议常量仍为 **2025-11-25**，支持 client/server 与 Streamable HTTP；不能据此宣称已支持 2026-07-28。原型需固定客户端、远程服务和 SDK 共同支持的版本，兼容性记录分别写清上游与下游版本。[发布页][sdk-release] · [版本常量][sdk-version]
- SDK README 声明 JVM / Native / JS / Wasm，Ktor 引擎由应用选择，并未给出本 App 的 Android 验证结果。下一步先验证依赖解析、D8、minSdk 26 以及 ARM64 设备启停，再选服务端引擎或给现有监听增加传输适配；不为研究 MCP 改成 Termux。[SDK][sdk]
- Streamable HTTP 要处理 JSON 和 SSE 两种响应。stdio 是同机子进程协议，桌面的 `npx` / `uvx` 命令不能直接作为局域网地址；已有 stdio 工具可继续在电脑运行，或由其所在机器提供 HTTP 入口。[HTTP][http] · [stdio][stdio]

本次复核读取了官方仓库当前版本入口、2026-07-28 规范、Kotlin SDK release 页面及协议常量。规范文档引用固定在 `2bbace557bbf2cb5d03d6f9b48905415cd7607b0`，SDK 固定在 `0.16.0` 对应的 `4c55826b6af00d616e1f5a285677ff53867cb941`。尚未集成依赖或运行 Android MCP 联调。

## 验收顺序

1. ARM64 / minSdk 26 构建通过，远程连接测试能列出真实工具；记录双方协议版本。
2. 一个实际客户端通过手机完成列出、调用、分页、JSON / SSE 响应、错误及取消；工具禁用后不可被调用。
3. 验证来源凭据、入站密钥、缺失或撤销密钥、非法 Origin；MCP 关闭时取消活动调用。
4. 多个客户端同时调用时结果与调用 ID 不串线，模型共享的配置和请求保持原有行为。
5. MCP 调用进入独立日志；模型 Token 不因工具调用而重复计数。之后再增加多个来源、目录合并或手机自有工具。

[openai-tools]: https://github.com/openai/openai-python/blob/abbd37925aa749ec35cd45cd94722e449f909388/src/openai/types/chat/chat_completion_function_tool_param.py
[versions]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/2bbace557bbf2cb5d03d6f9b48905415cd7607b0/docs/docs.json#L33
[spec-tag]: https://github.com/modelcontextprotocol/modelcontextprotocol/tree/2026-07-28
[changes]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/2bbace557bbf2cb5d03d6f9b48905415cd7607b0/docs/specification/2026-07-28/changelog.mdx
[http]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/2bbace557bbf2cb5d03d6f9b48905415cd7607b0/docs/specification/2026-07-28/basic/transports/streamable-http.mdx
[stdio]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/2bbace557bbf2cb5d03d6f9b48905415cd7607b0/docs/specification/2026-07-28/basic/transports/stdio.mdx
[tools]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/2bbace557bbf2cb5d03d6f9b48905415cd7607b0/docs/specification/2026-07-28/server/tools.mdx
[auth-security]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/2bbace557bbf2cb5d03d6f9b48905415cd7607b0/docs/specification/2026-07-28/basic/authorization/security-considerations.mdx
[sdk-release]: https://github.com/modelcontextprotocol/kotlin-sdk/releases/tag/0.16.0
[sdk-version]: https://github.com/modelcontextprotocol/kotlin-sdk/blob/4c55826b6af00d616e1f5a285677ff53867cb941/kotlin-sdk-core/src/commonMain/kotlin/io/modelcontextprotocol/kotlin/sdk/types/common.kt
[sdk]: https://github.com/modelcontextprotocol/kotlin-sdk/blob/4c55826b6af00d616e1f5a285677ff53867cb941/README.md
