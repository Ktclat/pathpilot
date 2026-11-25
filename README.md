# PathPilot

原生 Android AI 路由与共享应用。手机管理 Codex 订阅授权、DeepSeek API 和自定义 OpenAI 兼容供应商，本机应用与局域网设备使用统一密钥和模型别名调用。

使用 Kotlin、Jetpack Compose、Android 前台服务和轻量 JVM HTTP 路由核心；日常使用不需要 Termux、电脑常驻或命令行。

## 当前能力

- **Codex 订阅**：OpenAI 官方设备代码授权、浏览器登录、令牌刷新；登录后读取订阅模型目录，选择模型并保存为路由，也可手动填写模型 ID。
- **DeepSeek / 自定义供应商**：直接选择来源、HTTP/HTTPS 地址与密钥校验、可选读取模型列表、手动填写模型、随供应商一起保存路由、启停及删除。
- **原生界面与设置**：官方 Material 3 Expressive 主题和组件；主题可选跟随系统、浅色、深色。Android 12+ 默认使用 Monet 动态取色，可在设置中关闭；Android 8–11 使用统一备用配色。语言可选跟随系统、简体中文、English。应用图标、导航和操作图标使用一致样式，支持 Android 13+ 主题图标；ChatGPT/Codex 与 DeepSeek 显示各自品牌标记。
- **上游代理**：「设置 → 上游代理」支持跟随系统、HTTP、SOCKS5，覆盖模型请求、模型列表、Codex 登录/刷新与远程 MCP 请求。使用手机 VPN/TUN 时保留「跟随系统」。
- **模型管理**：「模型」页先选供应商，再管理该供应商下的共享模型，支持搜索与启用状态筛选。每项仍是客户端模型名 → 供应商 → 上游模型 ID 的路由；支持运行中更新配置。
- **统计**：底部独立「统计」页提供概览和请求记录；按今天、7 天、30 天或全部保留记录查看请求数、已记录 Token、成功率和平均耗时，以及请求/Token 趋势。按供应商或模型汇总，可进入对应请求；搜索、结果筛选和详情区分 HTTP 状态与流中断。本机持久保存最近 1,000 条，支持清空；没有用量数据时显示「—」，不推算费用或订阅剩余额度。
- **模型共享**：总开关控制前台服务；「本机」提供固定回环地址，「局域网」按网络类型和接口选择 IPv4 地址。支持多地址切换、网络变化刷新，VPN/蜂窝地址单独展开。共用端口和独立随机 `sk-` 访问密钥；升级保留已有密钥。
- **API**：`GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/responses`。Codex 支持聊天到 Responses 的转换；DeepSeek 仅使用 Chat Completions。详细边界见 [接口文档](docs/api.md)。
- **MCP**：「设置 → MCP」管理远程 Streamable HTTP 连接，支持无认证或 Bearer、获取工具、连接与工具开关。手机以 **MCP 2025-11-25 Tools** 网关提供每个连接的独立 `/mcp/<connectionId>` 地址，使用独立端口和 `sk-` 密钥，支持本机与局域网接入。MCP 共享和模型共享分别启停；工具调用由支持 MCP 的客户端发起。兼容范围见 [MCP 能力与后续方向](docs/mcp-roadmap.md)。
- **备份**：「设置 → 备份」导入或导出 `.ppbackup` 口令加密文件，包含供应商及密钥、模型路由、主题与语言、MCP 配置；Codex 登录状态可选，不含请求历史。导入前显示配置数量，默认保留本机端口、共享密钥与上游代理，可选择一并恢复连接设置。
- **凭据与记录**：Android Keystore AES-GCM 加密本机配置并原子写入，关闭 Android 系统自动备份；手动备份使用独立口令。模型请求记录仅包含模型、供应商、时间、状态、耗时与 Token 等元数据；MCP 最近调用记录工具名、连接、耗时和结果，不保存工具参数或结果正文。客户端只需手机提供的共享密钥。

## 在手机上使用

1. 安装构建生成的 ARM64 `app-debug.apk`，打开 PathPilot（64 位 ARM Android 8.0 / API 26 或更新版本）。本项目常规构建只打包 `arm64-v8a`，不提供 ARM32、x86 或 x64 安装包。
2. 在「供应商」中添加 Codex 订阅，打开 OpenAI 官方页面并输入设备代码。授权完成后回到应用，将自动打开模型选择并获取目录；勾选模型后保存。已有账号可在供应商卡片或编辑页点击「获取模型」。若账号设置要求，先在 ChatGPT 安全设置中启用设备代码登录。
3. 或选择 DeepSeek，填写 API Key，保留默认 `https://api.deepseek.com/v1`。自定义服务填写其 HTTP/HTTPS API 前缀，无认证的本地服务可以留空 API Key。可点击「获取模型」，选择需要共享的模型；也可直接手动填写。测试只读取模型列表，不发起生成请求。
4. 保存供应商时会为所选模型创建路由，重复模型不会重复创建，重名别名自动加后缀。在「模型」页选择供应商后，可添加或编辑共享模型名和上游模型 ID，例如 `deepseek-chat` → DeepSeek → `deepseek-chat`；供应商卡片的模型数量也能直接打开该列表。Codex 目录来自订阅接口，不用固定模型列表替代获取结果。
5. 打开「模型共享」总开关。在当前手机上的其他应用接入时选择「本机」，使用 `http://127.0.0.1:8317/v1`；电脑等设备选择「局域网」，复制与其所在网络对应的手机地址。端口以设置值为准。客户端类型选择 OpenAI 兼容，填写共享密钥和模型别名；「接入说明」提供 Base URL、完整端点及供应商支持范围。
6. 在底部「统计」页切换日期范围，查看趋势、供应商和模型汇总；进入「请求」可搜索和查看每次调用的状态、耗时与 Token 明细。统计基于当前保留记录，不是订阅额度查询。
7. 需要工具共享时，打开「设置 → MCP」，添加远程服务的完整 MCP URL，按需填写 Bearer 令牌。进入连接点击「获取工具」，选择共享的工具，再打开「MCP 共享」。将该连接的 MCP 地址和 MCP 访问密钥填写到支持 Streamable HTTP 的客户端；它与模型的 `/v1` 地址分别配置。
8. 在设置中选择主题、Monet 动态配色和语言。导出备份时设置口令，可勾选包含 Codex 登录状态；导入时先停止模型与 MCP 共享，选择文件、输入口令并检查恢复预览。默认保留当前手机的端口、共享密钥与上游代理；迁移这些设置时勾选恢复连接设置。恢复不启动共享。
9. 结束使用时分别停止已开启的模型或 MCP 共享；更改监听端口需先停止对应服务，密钥重置立即影响后续请求。

手机局域网入口使用 HTTP；官方上游默认 HTTPS，自定义地址按配置连接。路由器的客户端隔离会阻止设备互访，后台运行可从设置页进入系统电池设置。接口范围见 [API 文档](docs/api.md)。

## 构建与验证

Android Studio 打开仓库即可。工具链：JDK 17 或 21、Android SDK 37.0、Build Tools 36.0.0、Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.20。Gradle wrapper 固定发行包校验和。

```bash
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

Linux 云环境可使用已验证的引导脚本（依赖 `curl`、`unzip`、`tar`、`python3`、`sha256sum`）：

```bash
bash scripts/bootstrap-cloud.sh
source /workspace/toolchains/pathpilot-env.sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

脚本把工具链与缓存放在仓库外，通过标准 SDK 工具安装并校验依赖，处理 Java 工具的代理配置。APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。这是供开发验证的 debug 签名版本；正式发布应使用你自己的长期签名密钥。

当前开发版本为 `0.1.10`（versionCode 11）。常规构建原生库仅有 `lib/arm64-v8a/`；实际构建、交付和验证状态见 [验证记录](docs/validation.md)。

### UI 运行截图

正常安装包允许系统截图，密钥默认隐藏。云端 x86_64 模拟器使用 `./gradlew -PuiCapture=true :app:assembleDebug` 构建独立 `dev.pathpilot.app.capture` 测试包；数据与正常 ARM64 包隔离，该开关禁止 Release 任务。常规构建只输出 ARM64。

截图来自模拟器实际运行界面：[0.1.9 统计页与大字体截图](artifacts/screenshots/v0.1.9/README.md) · [0.1.8 用量与请求截图](artifacts/screenshots/v0.1.8/README.md) · [0.1.7 分组模型截图](artifacts/screenshots/v0.1.7/README.md) · [0.1.6 Codex 离线联调截图](artifacts/screenshots/v0.1.6/README.md) · [0.1.5 截图](artifacts/screenshots/v0.1.5/README.md) · [0.1.4 截图](artifacts/screenshots/v0.1.4/README.md) · [0.1.3 截图](artifacts/screenshots/v0.1.3/README.md) · [0.1.2 截图](artifacts/screenshots/v0.1.2/README.md) · [0.1.1 截图](artifacts/screenshots/v0.1.1/README.md)。运行条件、结果与限制见 [验证记录](docs/validation.md)。

测试使用本机 MockWebServer 和真实 HTTP 路由监听，覆盖鉴权、路由、SSE、订阅请求适配及 OAuth 刷新。它们不会访问付费推理服务。实际测试结果与尚未完成的设备/账号验证见 [验证记录](docs/validation.md)。

## GitHub Actions 与开发预发布

进入仓库 **Actions → Android ARM64 → Run workflow**，选择 `main`：

- 只验证构建：保持 `publish_prerelease=false`。成功后在该运行的 Artifacts 下载 `PathPilot-arm64-debug`，其中包含 ARM64 APK 和 `SHA256SUMS.txt`。
- 验证成功后发布：设置 `publish_prerelease=true`，填写新的 `release_tag`，例如 `v0.1.10-dev.1`。同一次运行先完成测试、Lint、签名与架构检查，再用其构建产物发布 GitHub 预发布版；失败不会发布。

推送到 `main` 或提交 PR 只做构建检查，不会发布。也可以在 `main` 的提交上创建并推送 `v0.1.10-dev.1` 形式的新标签，自动触发「构建 → 检查 → 预发布」。此方式不要求开发机器调用 GitHub API。发布会校验标签确实属于 `main` 的历史且版本匹配 APK；手动触发仍仅允许选择 `main`。已有 Release 不会被覆盖，标签不会被移动。上传失败时可能留下未公开的草稿，应检查运行日志后处理，不要删除已发布版本重试。

当前使用 **debug 签名**：不同 GitHub Runner 的签名可能不同，后续 APK 不保证可覆盖安装，卸载会清除本机配置和授权。开发预发布用于测试；正式发布前应配置长期签名密钥。本流程不会在仓库中保存私钥或真实供应商凭据。

## 结构与扩展

```text
app/   Compose 界面、主题与语言、加密配置与备份、模型/MCP 前台服务
core/  模型路由、Codex OAuth、Responses/Chat 转换、MCP 客户端与网关、JVM 测试
docs/  一手资料、复用评估、兼容性与验证记录
```

目前的路由按模型别名明确选择单个上游，不做隐式回退或重复发送生成请求。兼容 OpenAI API 的新服务可直接添加；不同认证或协议的 Coding Plan 需增加专用适配器，具体 zcode 服务和目标客户端明确后按其接口扩展。

界面和供应商流程：[设计与行为说明](docs/ui-and-providers.md)。统计页的信息结构及 CC Switch 一手源码参考见 [统计页参考](docs/statistics-reference.md)。

MCP 当前实现远程 HTTP Tools 共享，协议固定为 2025-11-25；2026-07-28、MCP OAuth、stdio、Resources、Prompts 和手机 Agent 留作后续适配。最近工具调用展示在 MCP 页面，与模型 Token 统计分开。实现细节见 [MCP 能力与后续方向](docs/mcp-roadmap.md)；设置、备份与 MCP 的 CC Switch / Magpie 一手依据见 [参考核查](docs/settings-mcp-reference.md)。

研究与设计依据：[开源复用评估](docs/reuse-assessment.md)、[Codex 官方源码核查](docs/codex-research.md)。未复制 RikkaHub 的 AGPL 实现。项目保留仓库原有 MIT 许可证；依赖及参考说明见 [第三方说明](docs/third-party.md)。

协作模型（包括 Astra）的实现和文案约定见 [AGENTS.md](AGENTS.md)。
