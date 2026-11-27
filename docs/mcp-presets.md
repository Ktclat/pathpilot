# 远程 MCP 预设

核查日期：2026-11-27（香港时间）。预设保存服务提供方的远程地址和认证方式，复用 PathPilot 的 HTTP 连接、工具目录与共享功能。手机无需为这些服务安装 Node.js、Python 或 Docker。

## 首批服务

| 服务 | 用途 | 官方远程地址 | 用户需要填写 |
| --- | --- | --- | --- |
| [Microsoft Learn][learn] | Microsoft 与 Azure 文档 | `https://learn.microsoft.com/api/mcp` | 无 |
| [OpenAI Docs][openai] | OpenAI 开发文档 | `https://developers.openai.com/mcp` | 无 |
| [Context7][context7] | 库与框架文档 | `https://mcp.context7.com/mcp` | Context7 API Key |
| [GitHub][github] | 仓库、Issue 与 PR | `https://api.githubcopilot.com/mcp/` | GitHub 个人访问令牌（PAT） |

Microsoft Learn 官方明确使用 Streamable HTTP，无需 API Key、登录或注册。OpenAI 官方安装命令直接使用 URL 添加开发者文档 MCP，未要求凭据。

Context7 的 README 将 API Key 标为推荐项，但同一版本的 [HTTP 认证源码][context7-auth]将认证模式默认设为 `required`，缺少 Key 时拒绝请求。**本 App 预设按服务端默认认证模式要求 Key**，通过官方支持的 `Authorization: Bearer <API_KEY>` 发送。托管服务的实际认证响应本次尚未验证。Key 可在 [Context7 Dashboard](https://context7.com/dashboard?tab=settings) 获取。

GitHub 官方明确支持远程 PAT 认证。用户填写令牌本身，App 添加 Bearer 前缀；令牌权限决定实际工具操作范围。创建入口为 [GitHub Personal access tokens](https://github.com/settings/personal-access-tokens)。GitHub OAuth 登录属于另一种接入流程，不由普通 PAT 表单代替。

前三项提供文档工具，Context7 使用自己的服务 Key，Microsoft Learn 与 OpenAI Docs 免填凭据。GitHub PAT 保留自己的原始格式；局域网客户端使用的是手机另外生成的 MCP 访问密钥。

## 添加流程

1. 在 MCP 页选择「添加连接」，从预设选择服务，或进入自定义连接。
2. Microsoft Learn 与 OpenAI Docs 可以直接添加；Context7 只补充 API Key，GitHub 只补充 PAT。服务地址和认证方式由预设提供。
3. 保存成功后进入该连接并自动获取工具。目录读取成功时展示实际工具；失败时保留已保存连接及具体错误，提供重新获取入口。
4. 后续修改仍使用统一连接编辑器。共享开关、工具选择及访问密钥沿用现有功能。

预设只负责减少输入。工具列表以服务的 `initialize` / `tools/list` 实际响应为准，使用当前统一客户端的协议协商和错误处理。

## 资料核查与在线结果

| 来源 | 固定提交 | 核查内容 |
| --- | --- | --- |
| `MicrosoftDocs/mcp` | `f0a9478a954c9b3a7c6525a5183466902ca60731` | README 的远程 URL、Streamable HTTP 与 No Auth 声明 |
| `openai/skills` | `49f948faa9258a0c61caceaf225e179651397431` | OpenAI Docs 官方安装命令 `codex mcp add openaiDeveloperDocs --url https://developers.openai.com/mcp` |
| `upstash/context7` | `a79b264e08bf441ee8f4ef65a27b93783b92d45d` | README 的远程 URL 与 Bearer header；HTTP 认证源码默认 `required`，与 README 的推荐措辞分别记录 |
| `github/github-mcp-server` | `7edc2dd123a8923db389de8772170663cb2d8e17` | README 的远程 HTTP URL 和 PAT Bearer 示例 |
| `modelcontextprotocol/typescript-sdk` | `951fe5603a7ec0b2dcb6a0b95d92cd86f93ad9e5`（v2.4.0） | Context7 所用 SDK 的支持版本包含 `2025-11-25` |

Context7 的 [HTTP 实现][context7-http]明确保留 2025 版协议的 legacy 路径，[依赖文件][context7-package]锁定 `@modelcontextprotocol/server` 2.4.0；对应 SDK 的 [支持版本常量][sdk-versions]包含 `2025-11-25`。源码包含当前协议路径，托管服务实际版本以初始化响应为准。Microsoft Learn 的公开 README 没有列出具体支持版本，需通过实际初始化确认。

本次对以上四个地址发送了无凭据 `initialize` 请求，使用 PathPilot 当前的协议版本 `2025-11-25`。四次请求均在云环境代理 CONNECT 阶段返回 `403 Forbidden`，未到达远程服务；因此没有远程协议协商或 `tools/list` 成功结果。GitHub 也未使用真实 PAT。本轮资料可以确认预填字段，在线兼容性仍需在可访问服务的网络中验证。

[无凭据探测记录](../artifacts/research/mcp-presets/remote-probes.json)分别记录代理 CONNECT 状态与空的上游状态；[来源索引](../artifacts/research/mcp-presets/sources.json)保存固定提交、文档路径和读取内容 SHA-256。

请求的 JSON-RPC 内容如下，未执行任何工具调用：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": { "name": "PathPilot-verification", "version": "0.0.1" }
  }
}
```

Notion 等要求 OAuth 的远程服务暂不进入本批静态预设。DeepWiki 的官方文档在本次环境也无法访问，留待核对后加入。远程目录与本地 MCP 包运行时的区别见 [官方来源与快捷接入](mcp-catalog-research.md)；GitHub 的逐字段配置和手机网关分工见 [Android MCP](android-mcp.md)。

[learn]: https://github.com/MicrosoftDocs/mcp/blob/f0a9478a954c9b3a7c6525a5183466902ca60731/README.md
[openai]: https://github.com/openai/skills/blob/49f948faa9258a0c61caceaf225e179651397431/skills/.curated/openai-docs/SKILL.md
[context7]: https://github.com/upstash/context7/blob/a79b264e08bf441ee8f4ef65a27b93783b92d45d/README.md
[context7-auth]: https://github.com/upstash/context7/blob/a79b264e08bf441ee8f4ef65a27b93783b92d45d/packages/mcp/src/lib/mcp-http-auth.ts#L78
[context7-http]: https://github.com/upstash/context7/blob/a79b264e08bf441ee8f4ef65a27b93783b92d45d/packages/mcp/src/index.ts#L595
[context7-package]: https://github.com/upstash/context7/blob/a79b264e08bf441ee8f4ef65a27b93783b92d45d/packages/mcp/package.json
[sdk-versions]: https://github.com/modelcontextprotocol/typescript-sdk/blob/951fe5603a7ec0b2dcb6a0b95d92cd86f93ad9e5/packages/core/src/constants.ts
[github]: https://github.com/github/github-mcp-server/blob/7edc2dd123a8923db389de8772170663cb2d8e17/README.md
