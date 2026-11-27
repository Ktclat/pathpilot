# 官方 MCP 来源与快捷接入

资料更新：2026-11-27（香港时间）。当前 App 已提供四项精选远程预设；在线 MCP Registry 搜索和本地包运行时尚未实现。本文保留服务发现与本地安装的来源依据，具体预设字段与本轮核查见 [远程 MCP 预设](mcp-presets.md)。

手机上的运行分工、GitHub PAT 逐字段配置及 Codex 客户端示例见 [在手机上使用 MCP](android-mcp.md)。

## 结论

PathPilot 已实现**选择远程预设 → 添加连接 → 自动获取工具**。服务由提供方运行，手机保存其 URL 和认证信息，复用现有 MCP 客户端及共享网关。界面使用「添加」；这项操作不下载远程服务的程序。

真正的一键安装本地 MCP 已有 `.mcpb` 方案，但需要宿主提供对应运行时和进程管理。当前 Android App 未包含 Node.js、Python 或 stdio 宿主，不能直接运行桌面 MCP 包；这与远程服务快捷接入是两项独立能力。

## 已查证的官方来源

| 来源 | 地址或形式 | 当前接入 |
| --- | --- | --- |
| [Microsoft Learn][learn] | `https://learn.microsoft.com/api/mcp` | 已提供无认证预设，官方文档明确无需 API Key、登录或注册。 |
| [OpenAI 开发者文档][openai] | `https://developers.openai.com/mcp` | 已提供无认证预设，官方安装命令按 URL 添加。 |
| Context7 | `https://mcp.context7.com/mcp` | 已提供 API Key 预设，使用 Bearer；官方 README 与 HTTP 默认认证配置的差异见 [核查说明](mcp-presets.md)。 |
| [GitHub][github] | `https://api.githubcopilot.com/mcp/` | 已提供 PAT Bearer 预设；OAuth 登录另行实现。 |
| [Notion][notion] | 官方托管的 Remote Notion MCP | 要求 OAuth，未加入本批静态预设；原自托管实现已不再积极维护。 |

预设字段来自一手资料，远程协议协商与工具目录仍需实际响应验证。

「官方」在这里指服务提供方自己的仓库或文档。MCP 协议组织维护的 [reference servers][servers] 是另一类：Filesystem、Memory、Fetch、Git 等参考实现展示协议与 SDK 用法，官方示例通过 `npx`、`uvx` 或 Python 启动。这些示例不是现成的 Android 安装包。

## 官方 Registry 能提供什么

[MCP Registry][registry] 提供发现与分发元数据。已核查其固定提交的 [OpenAPI][registry-api]：

- 列表接口为 `GET /v0.1/servers`，支持 `search`、`version=latest` 和 `metadata.nextCursor` 分页。`search` 是名称子串查询；默认列表含同一服务的多个版本，不能把首条结果当成最新版。
- `server.remotes` 描述远程连接，区分 `streamable-http` 与旧 `sse`，包含 URL、URL 变量和 HTTP headers。
- `server.packages` 描述软件包、运行时提示、参数、环境变量与 transport。记录存在于 Registry，并不表示当前 Android App 能运行该包。
- 输入元数据包含 `isRequired`、`isSecret`、默认值和候选值，适合生成凭据及租户等必要字段。
- Registry 发布时验证命名空间所有权；响应的 `io.modelcontextprotocol.registry/official` 是 Registry 自身的状态元数据，**不是服务已通过 PathPilot 兼容性测试的标记**。`isLatest` 也需要与 `status` 一起判断，已弃用条目仍可能是最新版。

当前静态预设随应用发布，尚未连接 Registry 搜索。后续实现在线搜索时，默认只显示状态有效的最新条目，并选择 `streamable-http` 入口。当前 App 仅支持无认证或单个 Bearer 令牌；需要其他 headers、未解析 URL 变量、OAuth 或旧 SSE 的条目，应显示具体缺少的配置能力，不能静默丢弃这些字段后保存。

## 当前手机流程

1. 在 MCP 页选择「添加连接」，列表展示四项预设及「自定义连接」。
2. Learn 与 OpenAI Docs 直接添加；Context7 填 API Key，GitHub 填 PAT，令牌表单提供官方获取入口。
3. 保存成功后进入连接并自动初始化、读取 `tools/list`。失败时保留已保存连接，详情中可重新获取；成功后按实际目录选择共享工具。
4. 后续编辑复用完整连接表单。相同地址和令牌直接打开已有连接，同一地址的不同账号可分别配置。

预设仅减少输入，协议、代理、工具选择和共享状态继续使用统一实现。在线 Registry 搜索、MCP OAuth 与本地 stdio 宿主分别按所需能力扩展。

## 本地包的一键安装

[Anthropic MCP Bundles（MCPB）][mcpb] 将本地 MCP 服务及 `manifest.json` 放入 `.mcpb` ZIP 包。官方文档说明桌面宿主可以提供单击安装，并列出 Node.js、Python、binary 和 uv 等服务类型；[manifest][mcpb-manifest] 的平台与运行时说明针对 macOS、Windows、Linux。Claude 桌面端自带 Node.js 是其低安装成本的具体前提。

Android 后续若需要本地工具，应单独评估 ARM64 Android 运行时、文件授权、进程生命周期和包依赖；仅支持 ARM64 并不能让普通 Linux ARM64 二进制自动成为 Android 可执行程序。可以先把明确需要的手机工具实现为原生 MCP 服务，再考虑通用包宿主。本轮保持原生 APK 与现有远程 HTTP 接入方式。

## 资料与在线记录

以下为 Registry、参考服务与 MCPB 的固定版本资料。四项当前预设的最新认证核查、Context7 HTTP 源码与初始化结果单独记录在 [预设文档](mcp-presets.md)。

| 仓库 | 核查提交 |
| --- | --- |
| `modelcontextprotocol/registry` | `0c74fa6fa58a40ab43f900c7c8a77a0cd0634a55` |
| `MicrosoftDocs/mcp` | `f0a9478a954c9b3a7c6525a5183466902ca60731` |
| `github/github-mcp-server` | `7edc2dd123a8923db389de8772170663cb2d8e17` |
| `openai/skills` | `49f948faa9258a0c61caceaf225e179651397431` |
| `makenotion/notion-mcp-server` | `cf16e5c20bfbfb98ecec1227f210ef0ae3abebe5` |
| `modelcontextprotocol/servers` | `c799c8552b0f27b7a33a7b9233773da144dddcf5` |
| `anthropics/mcpb` | `70fe3b34cd6dff1b3bba046638edc72a6467a4fb` |

此前访问 Registry 及相关 MCP 域名时，云环境代理在 CONNECT 阶段返回 `403 Forbidden`；本轮四项预设的初始化尝试也未到达远程服务。因此目前没有在线 Registry 搜索、远程 `initialize` 或 `tools/list` 成功记录。预设字段已核查，服务兼容性仍待在可访问网络中联调。

[registry]: https://github.com/modelcontextprotocol/registry/blob/0c74fa6fa58a40ab43f900c7c8a77a0cd0634a55/README.md
[registry-api]: https://github.com/modelcontextprotocol/registry/blob/0c74fa6fa58a40ab43f900c7c8a77a0cd0634a55/docs/reference/api/openapi.yaml
[learn]: https://github.com/MicrosoftDocs/mcp/blob/f0a9478a954c9b3a7c6525a5183466902ca60731/README.md
[github]: https://github.com/github/github-mcp-server/blob/7edc2dd123a8923db389de8772170663cb2d8e17/README.md
[openai]: https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/openai-docs/SKILL.md
[notion]: https://github.com/makenotion/notion-mcp-server/blob/cf16e5c20bfbfb98ecec1227f210ef0ae3abebe5/README.md
[servers]: https://github.com/modelcontextprotocol/servers/blob/c799c8552b0f27b7a33a7b9233773da144dddcf5/README.md
[mcpb]: https://github.com/anthropics/mcpb/blob/70fe3b34cd6dff1b3bba046638edc72a6467a4fb/README.md
[mcpb-manifest]: https://github.com/anthropics/mcpb/blob/70fe3b34cd6dff1b3bba046638edc72a6467a4fb/MANIFEST.md
