# 设置、备份与 MCP 参考核查

核查日期：2026-11-25。本文件记录一手源码和协议事实，以及本轮 Android 实现的取舍。参考项目仅做源码阅读，未在本轮运行它们的界面或测试；PathPilot 的构建、模拟器和联调结果另见交付记录。

## 固定来源

| 项目 | 本次读取版本 | 核查范围 |
| --- | --- | --- |
| [CC Switch][cc] | `bf4cc69fe00e762a17d2ee40f409640cde8cef15` | 设置分组、主题与语言、导入导出、备份、MCP 表单 |
| [Magpie][magpie] | `e66d872a8eaecc285b0d597917d6b520035712ec` | 设置、口令备份、设备本地配置、MCP 路由 |
| [MCP 官方规范][spec] | `b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48` | 2025-11-25 与 2026-07-28 的 HTTP、版本、Tools 和认证约定 |

此前调查未定位 Magpie。本轮找到 `yetone/magpie`：README 明确列出 ChatGPT 订阅、Codex、DeepSeek、统一网关和模型路由，与本项目讨论对象相符。它本身面向桌面及 CLI/Web，不能据此称为原生 Android 复用方案。[项目介绍][magpie]

## 设置如何组织

CC Switch 的设置组件采用「分组标题 → 卡片 → 设置行」。`SettingsRow.description` 用于路径、地址等实际数据，规则解释由帮助入口承载；主题提供跟随系统、浅色、深色，语言修改自动保存。[布局组件][cc-layout] · [通用设置][cc-general]

Magpie 的设置测试明确检查：名称足够的设置行没有副文案，语言是显示当前选择的一个入口，选中后保存并更新界面。它也提供 `system / light / dark`；语言列表含跟随系统、英语、简体中文、繁体中文、日语和德语。具体服务的设置归属供应商编辑器。[设置实现][magpie-settings] · [设置文案与语言测试][magpie-general-test] · [供应商选项归位][magpie-groups-test]

PathPilot 的移动端采用以下信息结构；不是把桌面全部选项搬进手机：

| 分组 | 选项与交互 |
| --- | --- |
| 外观 | 主题：跟随系统／浅色／深色；动态配色：Android 12+ 采用 Monet，旧版本使用备用配色 |
| 语言 | 跟随系统／简体中文／English；一行显示当前值，选择后全局生效 |
| 连接 | 现有端口、上游代理和后台运行入口；显示当前值 |
| MCP | 独立连接列表、添加连接、获取工具、连接及工具共享状态；共享地址不混入模型 URL |
| 数据 | 导出备份、导入备份；文件操作交给 Android 系统文件选择器 |
| 关于 | 版本与项目链接 |

页面使用统一的 Material 字体层级和触控尺寸。主题、语言、开关名称本身可解释的地方不追加说明段落。确实影响操作结果的短说明保留，例如恢复将替换哪些配置、旧密钥何时失效。供应商专属认证和模型选项仍留在供应商详情。

## 备份可以复用的行为

CC Switch 导出的是 SQL 文件；导入先选文件，再执行，界面区分成功、失败和附加步骤失败。备份列表提供创建、恢复、重命名、删除，恢复有确认并保留恢复前备份。这里没有足够证据把其 SQL 导出描述成口令加密。[导入导出界面][cc-import-ui] · [导入导出实现][cc-import] · [备份列表][cc-backup]

Magpie 备份为口令密封包：源码声明 AES-256-GCM、PBKDF2-SHA256，迭代数为 600,000。供应商凭据可排除；订阅登录由 `WithSignIns` 单独加入，用量由 `WithUsage` 单独加入，MCP 配置属于 Library。同步和恢复通过 `KeepOwn` 保留目标设备的代理、端口及 LAN 开关等本机设置。[备份格式与实现][magpie-backup] · [同步与恢复边界][magpie-sync]

对 PathPilot 的具体取舍：

1. 提供应用自己的版本化备份格式，不宣称直接兼容 CC Switch SQL 或 Magpie 文件。
2. 含供应商密钥、订阅凭据或 MCP 凭据的可迁移备份用口令加密；本机 Keystore 密钥不能当作跨设备恢复口令。
3. 导出前明确包含哪些数据。导入先解密、解析、校验，再展示供应商、路由和 MCP 数量及替换范围；确认后统一应用。
4. 格式不支持、口令错误或文件损坏应给出实际结果，不能把空配置当作成功恢复。完成持久化后才显示成功。
5. 恢复配置不等于启动监听。目标手机的服务运行状态由用户现有共享开关管理。

以上是产品取舍，具体导出字段、恢复语义和自动备份能力以 PathPilot 的实现为准，不能从参考项目反推已完成。

## MCP 页面与网关边界

CC Switch 的 MCP 表单区分 stdio、HTTP、SSE：命令型填写 command、args、env、cwd；远程型填写 URL 和 headers。其配置规范化代码还处理 `streamable-http` 命名及其他客户端格式。桌面的命令与应用启用矩阵适合配置桌面 Agent，不能直接成为手机的远程服务地址。[MCP 表单][cc-mcp-form] · [配置转换][cc-mcp-draft]

Magpie 的 MCP router 为客户端提供独立 Streamable HTTP URL，汇总 Library 服务的工具；服务和工具有启用状态。文档明确：关闭时既不列出也不执行，Tools 列表和调用权限一致；调用日志记录服务、工具、耗时、结果，不记录工具参数。其聚合工具名使用服务前缀处理冲突。[MCP router][magpie-mcp]

本轮 PathPilot 固定实现 **MCP 2025-11-25、Streamable HTTP、Tools**。添加连接只需要名称、完整 HTTP(S) URL、认证方式（无／Bearer）及令牌；「获取工具」实际完成连接初始化与分页查询。详情按连接展示工具，启用和共享状态影响实际列出与调用。入站共享凭据与该连接的上游凭据分开使用。

MCP URL 保留服务完整路径，不自动添加 `/v1`。客户端的模型入口仍是 OpenAI 兼容 API；工具入口是 MCP JSON-RPC。仅增加 MCP 服务不会自动让模型调用工具，工具执行流程由支持 MCP 的客户端负责。stdio、OAuth、Resources、Prompts 和手机 Agent 暂不作为本轮已支持能力。

## MCP 2025-11-25 必须兑现的协议行为

| 环节 | 官方约定及实现含义 |
| --- | --- |
| 初始化 | 第一个交互为 `initialize`，含 `protocolVersion`、`capabilities`、`clientInfo`；校验服务端版本与能力后发送 `notifications/initialized`。不支持的协商版本不能伪装成连接成功。 |
| HTTP 请求 | 每条 JSON-RPC 请求、通知或响应分别 POST；客户端 `Accept` 同时接受 `application/json` 和 `text/event-stream`。初始化后的请求带协商版本的 `MCP-Protocol-Version`。 |
| HTTP 响应 | JSON-RPC 请求可返回单个 JSON 或 SSE，客户端必须处理两者；成功接收通知或响应时返回 HTTP 202 空 body。SSE 中在对应响应前可能出现通知。 |
| Session | 服务端可在初始化响应返回 `Mcp-Session-Id`；若返回，后续请求都带它。会话失效的 404 要重新初始化；不能因此自动重放可能已执行的 `tools/call`。不使用会话的服务端不需要制造 Session ID。 |
| GET / DELETE | GET 可建立服务端 SSE 通知流，未提供该流时返回 405；结束会话可 DELETE，服务端允许返回 405。不能因 GET 405 判定 POST MCP 不可用。 |
| Origin | 服务端必须验证请求中存在的 Origin；非法值返回 403。原生客户端通常无 Origin，不能把“缺失 Origin”当作非法 Origin。 |
| 列表 | `tools/list` 支持不透明 cursor；继续请求直至 `nextCursor` 缺失。不能只显示第一页，也不能解析或改写 cursor。 |
| 调用 | `tools/call` 使用 name、arguments；工具执行错误通过结果中的 `isError` 表示，与协议 JSON-RPC 错误区分。共享列表与实际可调用集合一致。 |
| 认证 | 静态 Bearer 配置只覆盖接受此凭据的服务。完整 MCP OAuth 还涉及受保护资源元数据、授权服务器发现与 OAuth 流程，不能把一个 Token 输入框称作 OAuth。 |

依据：[生命周期][legacy-lifecycle] · [HTTP 传输][legacy-http] · [工具][legacy-tools] · [分页][legacy-pagination] · [认证][legacy-auth]。

## 为什么不把 2026-07-28 标成已支持

官方 2026-07-28 已改变生命周期与 HTTP 契约：取消 `initialize` 和协议 Session，改为每次请求的版本／客户端能力元数据，并定义 `server/discover`。HTTP 必须按请求带 `MCP-Protocol-Version`、`Mcp-Method`，特定方法还需 `Mcp-Name`，带 `x-mcp-header` 的参数也有对应规则。它取消独立 GET 通知流，关闭 SSE 的取消语义也不同。[2026 版本协定][modern-versioning] · [2026 HTTP][modern-http]

因此本轮按一个明确版本实现与联调；将来接入 2026 服务时增加版本对应的完整适配，再分别记录上游和下游兼容性。不是修改一个版本字符串就得到兼容。

[cc]: https://github.com/farion1231/cc-switch/tree/bf4cc69fe00e762a17d2ee40f409640cde8cef15
[cc-layout]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/settings/SettingsLayout.tsx
[cc-general]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/settings/sections/GeneralSection.tsx
[cc-import-ui]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/settings/ImportExportSection.tsx
[cc-import]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/hooks/useImportExport.ts
[cc-backup]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/settings/BackupListSection.tsx
[cc-mcp-form]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/mcp/McpFormModal.tsx
[cc-mcp-draft]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/mcp/mcpDraft.ts
[magpie]: https://github.com/yetone/magpie/blob/e66d872a8eaecc285b0d597917d6b520035712ec/README.md
[magpie-settings]: https://github.com/yetone/magpie/blob/e66d872a8eaecc285b0d597917d6b520035712ec/internal/gui/assets/app.js#L19649
[magpie-general-test]: https://github.com/yetone/magpie/blob/e66d872a8eaecc285b0d597917d6b520035712ec/internal/gui/tests/settings-general.test.cjs
[magpie-groups-test]: https://github.com/yetone/magpie/blob/e66d872a8eaecc285b0d597917d6b520035712ec/internal/gui/tests/settings-groups.test.cjs
[magpie-backup]: https://github.com/yetone/magpie/blob/e66d872a8eaecc285b0d597917d6b520035712ec/internal/backup/backup.go
[magpie-sync]: https://github.com/yetone/magpie/blob/e66d872a8eaecc285b0d597917d6b520035712ec/docs/subsystems/sync-backup.md
[magpie-mcp]: https://github.com/yetone/magpie/blob/e66d872a8eaecc285b0d597917d6b520035712ec/docs/subsystems/mcp-router.md
[spec]: https://github.com/modelcontextprotocol/modelcontextprotocol/tree/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48
[legacy-lifecycle]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/basic/lifecycle.mdx
[legacy-http]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/basic/transports.mdx
[legacy-tools]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/server/tools.mdx
[legacy-pagination]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/server/utilities/pagination.mdx
[legacy-auth]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2025-11-25/basic/authorization.mdx
[modern-versioning]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/basic/versioning.mdx
[modern-http]: https://github.com/modelcontextprotocol/modelcontextprotocol/blob/b0be8f0f2481be8d5ea5294cf3fa57cd03cedc48/docs/specification/2026-07-28/basic/transports/streamable-http.mdx
