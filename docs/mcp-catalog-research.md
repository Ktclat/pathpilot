# 官方 MCP 来源与快捷接入

核查日期：2026-11-26（香港时间）。本文给出接入方案和一手依据；尚未实现的目录功能不计入当前 App 能力。

手机上的运行分工、GitHub PAT 逐字段配置及 Codex 客户端示例见 [在手机上使用 MCP](android-mcp.md)。

## 结论

PathPilot 适合先做**远程服务目录 → 添加连接 → 获取工具**。远程 MCP 服务已经由提供方运行，手机只需保存 URL 和认证信息，接着使用现有 MCP 客户端及共享网关。界面按钮使用「添加」，不把保存远程连接称为下载或安装程序。

真正的一键安装本地 MCP 已有 `.mcpb` 方案，但需要宿主提供对应运行时和进程管理。当前 Android App 未包含 Node.js、Python 或 stdio 宿主，不能直接运行桌面 MCP 包；这与远程服务快捷接入是两项独立能力。

## 已查证的官方来源

| 来源 | 地址或形式 | 认证依据 | 对当前 PathPilot 的判断 |
| --- | --- | --- | --- |
| [Microsoft Learn][learn] | `https://learn.microsoft.com/api/mcp` | 官方文档明确无需 API Key、登录或注册，使用 Streamable HTTP | 可作为首批预填连接；仍需验证与 App 固定的 2025-11-25 协议握手。 |
| [GitHub][github] | `https://api.githubcopilot.com/mcp/` | 官方配置同时给出 OAuth 与 `Authorization: Bearer <PAT>` | 可走现有 Bearer 输入；需要用户自己的 PAT，并实测协议和工具目录。OAuth 登录另行实现。 |
| [OpenAI 开发者文档][openai] | `https://developers.openai.com/mcp` | OpenAI 官方仓库提供直接按 URL 添加的配置命令 | URL 已由官方资料确认；本次未取得在线认证与协议响应，不标为已兼容。 |
| [Notion][notion] | 官方托管的 Remote Notion MCP | 官方仓库明确远程方案使用 OAuth，并将原自托管实现标为不再积极维护 | 先实现 MCP OAuth，再加入可直接连接的目录；不将普通 Notion API Token 当作远程 MCP 登录。 |

「官方」在这里指服务提供方自己的仓库或文档。MCP 协议组织维护的 [reference servers][servers] 是另一类：Filesystem、Memory、Fetch、Git 等参考实现展示协议与 SDK 用法，官方示例通过 `npx`、`uvx` 或 Python 启动。这些示例不是现成的 Android 安装包。

## 官方 Registry 能提供什么

[MCP Registry][registry] 提供发现与分发元数据。已核查其固定提交的 [OpenAPI][registry-api]：

- 列表接口为 `GET /v0.1/servers`，支持 `search`、`version=latest` 和 `metadata.nextCursor` 分页。`search` 是名称子串查询；默认列表含同一服务的多个版本，不能把首条结果当成最新版。
- `server.remotes` 描述远程连接，区分 `streamable-http` 与旧 `sse`，包含 URL、URL 变量和 HTTP headers。
- `server.packages` 描述软件包、运行时提示、参数、环境变量与 transport。记录存在于 Registry，并不表示当前 Android App 能运行该包。
- 输入元数据包含 `isRequired`、`isSecret`、默认值和候选值，适合生成凭据及租户等必要字段。
- Registry 发布时验证命名空间所有权；响应的 `io.modelcontextprotocol.registry/official` 是 Registry 自身的状态元数据，**不是服务已通过 PathPilot 兼容性测试的标记**。`isLatest` 也需要与 `status` 一起判断，已弃用条目仍可能是最新版。

首版目录可先维护少量已核查的官方远程 URL。接入 Registry 搜索时，默认只显示状态有效的最新条目，并选择 `streamable-http` 入口。当前 App 仅支持无认证或单个 Bearer 令牌；需要其他 headers、未解析 URL 变量、OAuth 或旧 SSE 的条目，应显示具体缺少的配置能力，不能静默丢弃这些字段后保存。

## 建议的手机流程

1. 在 MCP 页的添加入口提供「从目录添加」和「自定义连接」。目录每项显示服务名、提供方、简短用途和认证方式。
2. 选择来源后复用现有编辑页，预填名称与 URL，只询问该来源需要的字段。详情提供官方文档链接。
3. 保存后执行现有初始化和 `tools/list`；按实际响应展示连接状态、协议版本及工具。获取成功后再允许用户选择共享的工具。
4. 后续接入 Registry 搜索和 MCP OAuth。OAuth 包括受保护资源发现、授权服务器发现、浏览器 PKCE、令牌保存与刷新，不以手工粘贴静态令牌替代完整流程。

不为每个来源新增独立的模型路由或工具调用实现；目录负责填入配置，连接、代理、工具选择、共享状态与错误展示继续使用当前统一实现。

## 本地包的一键安装

[Anthropic MCP Bundles（MCPB）][mcpb] 将本地 MCP 服务及 `manifest.json` 放入 `.mcpb` ZIP 包。官方文档说明桌面宿主可以提供单击安装，并列出 Node.js、Python、binary 和 uv 等服务类型；[manifest][mcpb-manifest] 的平台与运行时说明针对 macOS、Windows、Linux。Claude 桌面端自带 Node.js 是其低安装成本的具体前提。

Android 后续若需要本地工具，应单独评估 ARM64 Android 运行时、文件授权、进程生命周期和包依赖；仅支持 ARM64 并不能让普通 Linux ARM64 二进制自动成为 Android 可执行程序。可以先把明确需要的手机工具实现为原生 MCP 服务，再考虑通用包宿主。本轮保持原生 APK 与现有远程 HTTP 接入方式。

## 本次验证与边界

已从官方 GitHub 仓库读取并核对以下固定版本资料；只做资料读取及无凭据初始化尝试，没有调用远程工具或使用真实账户。

| 仓库 | 核查提交 |
| --- | --- |
| `modelcontextprotocol/registry` | `0c74fa6fa58a40ab43f900c7c8a77a0cd0634a55` |
| `MicrosoftDocs/mcp` | `f0a9478a954c9b3a7c6525a5183466902ca60731` |
| `github/github-mcp-server` | `7edc2dd123a8923db389de8772170663cb2d8e17` |
| `openai/skills` | `49f948faa9258a0c61caceaf225e179651397431` |
| `makenotion/notion-mcp-server` | `cf16e5c20bfbfb98ecec1227f210ef0ae3abebe5` |
| `modelcontextprotocol/servers` | `c799c8552b0f27b7a33a7b9233773da144dddcf5` |
| `anthropics/mcpb` | `70fe3b34cd6dff1b3bba046638edc72a6467a4fb` |

当前云环境访问 Registry、OpenAI 文档 MCP、Microsoft Learn MCP、GitHub MCP、Notion MCP 和 DeepWiki 域名时，代理在 CONNECT 阶段返回 `403 Forbidden`，请求未到达服务。因此本次没有在线 Registry 搜索结果，也没有远程 `initialize` 或 `tools/list` 成功结果。这是本次环境的访问结果，不代表这些服务不可用或与 PathPilot 不兼容。下一次联调需记录实际协议版本、认证方式及工具目录，再把来源标为已验证。

[registry]: https://github.com/modelcontextprotocol/registry/blob/0c74fa6fa58a40ab43f900c7c8a77a0cd0634a55/README.md
[registry-api]: https://github.com/modelcontextprotocol/registry/blob/0c74fa6fa58a40ab43f900c7c8a77a0cd0634a55/docs/reference/api/openapi.yaml
[learn]: https://github.com/MicrosoftDocs/mcp/blob/f0a9478a954c9b3a7c6525a5183466902ca60731/README.md
[github]: https://github.com/github/github-mcp-server/blob/7edc2dd123a8923db389de8772170663cb2d8e17/README.md
[openai]: https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/openai-docs/SKILL.md
[notion]: https://github.com/makenotion/notion-mcp-server/blob/cf16e5c20bfbfb98ecec1227f210ef0ae3abebe5/README.md
[servers]: https://github.com/modelcontextprotocol/servers/blob/c799c8552b0f27b7a33a7b9233773da144dddcf5/README.md
[mcpb]: https://github.com/anthropics/mcpb/blob/70fe3b34cd6dff1b3bba046638edc72a6467a4fb/README.md
[mcpb-manifest]: https://github.com/anthropics/mcpb/blob/70fe3b34cd6dff1b3bba046638edc72a6467a4fb/MANIFEST.md
