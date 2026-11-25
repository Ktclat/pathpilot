# PathPilot

原生 Android AI 路由与共享应用。手机管理 Codex 订阅授权、DeepSeek API 和自定义 OpenAI 兼容供应商，本机应用与局域网设备使用统一密钥和模型别名调用。

使用 Kotlin、Jetpack Compose、Android 前台服务和轻量 JVM HTTP 路由核心；日常使用不需要 Termux、电脑常驻或命令行。

## 当前能力

- **Codex 订阅**：OpenAI 官方设备代码授权、浏览器登录、令牌刷新；登录后读取订阅模型目录，选择模型并保存为路由，也可手动填写模型 ID。
- **DeepSeek / 自定义供应商**：直接选择来源、HTTP/HTTPS 地址与密钥校验、可选读取模型列表、手动填写模型、随供应商一起保存路由、启停及删除。
- **原生界面**：官方 Material 3 Expressive 主题和组件；Android 12+ 跟随系统 Monet 动态取色与明暗主题，Android 8–11 使用统一备用配色。应用图标、导航和操作图标使用一致样式，支持 Android 13+ 主题图标；ChatGPT/Codex 与 DeepSeek 显示各自品牌标记。
- **上游代理**：「设置 → 上游代理」支持跟随系统、HTTP、SOCKS5，覆盖模型请求、模型列表与 Codex 登录/刷新。使用手机 VPN/TUN 时保留「跟随系统」。
- **模型管理**：「模型」页先选供应商，再管理该供应商下的共享模型，支持搜索与启用状态筛选。每项仍是客户端模型名 → 供应商 → 上游模型 ID 的路由；支持运行中更新配置。
- **统计**：底部独立「统计」页提供概览和请求记录；按今天、7 天、30 天或全部保留记录查看请求数、已记录 Token、成功率和平均耗时，以及请求/Token 趋势。按供应商或模型汇总，可进入对应请求；搜索、结果筛选和详情区分 HTTP 状态与流中断。本机持久保存最近 1,000 条，支持清空；没有用量数据时显示「—」，不推算费用或订阅剩余额度。
- **模型共享**：总开关控制前台服务；「本机」提供固定回环地址，「局域网」按网络类型和接口选择 IPv4 地址。支持多地址切换、网络变化刷新，VPN/蜂窝地址单独展开。共用端口和独立随机 `sk-` 访问密钥；升级保留已有密钥。
- **API**：`GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/responses`。Codex 支持聊天到 Responses 的转换；DeepSeek 仅使用 Chat Completions。详细边界见 [接口文档](docs/api.md)。
- **隐私**：Android Keystore AES-GCM 加密整个配置，原子写入，禁用备份；请求记录仅包含模型、供应商、时间、状态、耗时与 Token 用量等元数据，不保存对话正文。局域网请求不携带上游凭据。

## 在手机上使用

1. 安装构建生成的 ARM64 `app-debug.apk`，打开 PathPilot（64 位 ARM Android 8.0 / API 26 或更新版本）。本项目常规构建只打包 `arm64-v8a`，不提供 ARM32、x86 或 x64 安装包。
2. 在「供应商」中添加 Codex 订阅，打开 OpenAI 官方页面并输入设备代码。授权完成后回到应用，将自动打开模型选择并获取目录；勾选模型后保存。已有账号可在供应商卡片或编辑页点击「获取模型」。若账号设置要求，先在 ChatGPT 安全设置中启用设备代码登录。
3. 或选择 DeepSeek，填写 API Key，保留默认 `https://api.deepseek.com/v1`。自定义服务填写其 HTTP/HTTPS API 前缀，无认证的本地服务可以留空 API Key。可点击「获取模型」，选择需要共享的模型；也可直接手动填写。测试只读取模型列表，不发起生成请求。
4. 保存供应商时会为所选模型创建路由，重复模型不会重复创建，重名别名自动加后缀。在「模型」页选择供应商后，可添加或编辑共享模型名和上游模型 ID，例如 `deepseek-chat` → DeepSeek → `deepseek-chat`；供应商卡片的模型数量也能直接打开该列表。Codex 目录来自订阅接口，不用固定模型列表替代获取结果。
5. 打开「模型共享」总开关。在当前手机上的其他应用接入时选择「本机」，使用 `http://127.0.0.1:8317/v1`；电脑等设备选择「局域网」，复制与其所在网络对应的手机地址。端口以设置值为准。客户端类型选择 OpenAI 兼容，填写共享密钥和模型别名；「接入说明」提供 Base URL、完整端点及供应商支持范围。
6. 在底部「统计」页切换日期范围，查看趋势、供应商和模型汇总；进入「请求」可搜索和查看每次调用的状态、耗时与 Token 明细。统计基于当前保留记录，不是订阅额度查询。
7. 结束使用时停止共享；更改监听端口需先停止服务，密钥重置立即影响后续请求。

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

本轮版本为 `0.1.9`（versionCode 10），ARM64 交付文件名为 `artifacts/PathPilot-0.1.9-arm64.apk`。常规构建原生库仅有 `lib/arm64-v8a/`；实际构建状态见 [验证记录](docs/validation.md)。

### UI 运行截图

正常安装包允许系统截图，密钥默认隐藏。云端 x86_64 模拟器使用 `./gradlew -PuiCapture=true :app:assembleDebug` 构建独立 `dev.pathpilot.app.capture` 测试包；数据与正常 ARM64 包隔离，该开关禁止 Release 任务。常规构建只输出 ARM64。

截图来自模拟器实际运行界面：[0.1.9 统计页与大字体截图](artifacts/screenshots/v0.1.9/README.md) · [0.1.8 用量与请求截图](artifacts/screenshots/v0.1.8/README.md) · [0.1.7 分组模型截图](artifacts/screenshots/v0.1.7/README.md) · [0.1.6 Codex 离线联调截图](artifacts/screenshots/v0.1.6/README.md) · [0.1.5 截图](artifacts/screenshots/v0.1.5/README.md) · [0.1.4 截图](artifacts/screenshots/v0.1.4/README.md) · [0.1.3 截图](artifacts/screenshots/v0.1.3/README.md) · [0.1.2 截图](artifacts/screenshots/v0.1.2/README.md) · [0.1.1 截图](artifacts/screenshots/v0.1.1/README.md)。运行条件、结果与限制见 [验证记录](docs/validation.md)。

测试使用本机 MockWebServer 和真实 HTTP 路由监听，覆盖鉴权、路由、SSE、订阅请求适配及 OAuth 刷新。它们不会访问付费推理服务。实际测试结果与尚未完成的设备/账号验证见 [验证记录](docs/validation.md)。

## GitHub Actions 与开发预发布

进入仓库 **Actions → Android ARM64 → Run workflow**，选择 `main`：

- 只验证构建：保持 `publish_prerelease=false`。成功后在该运行的 Artifacts 下载 `PathPilot-arm64-debug`，其中包含 ARM64 APK 和 `SHA256SUMS.txt`。
- 验证成功后发布：设置 `publish_prerelease=true`，填写新的 `release_tag`，例如 `v0.1.9-dev.1`。同一次运行先完成测试、Lint、签名与架构检查，再用其构建产物发布 GitHub 预发布版；失败不会发布。

推送到 `main` 或提交 PR 只做构建检查，不会发布。也可以在 `main` 的提交上创建并推送 `v0.1.9-dev.1` 形式的新标签，自动触发「构建 → 检查 → 预发布」。此方式不要求开发机器调用 GitHub API。发布会校验标签确实属于 `main` 的历史且版本匹配 APK；手动触发仍仅允许选择 `main`。已有 Release 不会被覆盖，标签不会被移动。上传失败时可能留下未公开的草稿，应检查运行日志后处理，不要删除已发布版本重试。

当前使用 **debug 签名**：不同 GitHub Runner 的签名可能不同，后续 APK 不保证可覆盖安装，卸载会清除本机配置和授权。开发预发布用于测试；正式发布前应配置长期签名密钥。本流程不会在仓库中保存私钥或真实供应商凭据。

## 结构与扩展

```text
app/   Compose 界面、Android Keystore、前台服务与通知
core/  HTTP 路由、数据模型、Codex OAuth、Responses/Chat 转换、JVM 测试
docs/  一手资料、复用评估、兼容性与验证记录
```

目前的路由按模型别名明确选择单个上游，不做隐式回退或重复发送生成请求。兼容 OpenAI API 的新服务可直接添加；不同认证或协议的 Coding Plan 需增加专用适配器，不能仅凭服务名称认定兼容。后续具体 zcode 服务、Magpie 仓库地址和目标客户端明确后，可按相同边界扩展。

界面和供应商流程：[设计与行为说明](docs/ui-and-providers.md)。统计页的信息结构及 CC Switch 一手源码参考见 [统计页参考](docs/statistics-reference.md)。

MCP 尚未实现。下一阶段建议增加远程 MCP 连接管理及独立的本机/局域网共享入口，由客户端继续驱动模型与工具调用。工具调用与模型 Token 分开统计；具体页面、实施顺序及协议版本核查见 [MCP 后续方案](docs/mcp-roadmap.md)。

研究与设计依据：[开源复用评估](docs/reuse-assessment.md)、[Codex 官方源码核查](docs/codex-research.md)。未复制 RikkaHub 的 AGPL 实现。项目保留仓库原有 MIT 许可证；依赖及参考说明见 [第三方说明](docs/third-party.md)。

协作模型（包括 Astra）的实现和文案约定见 [AGENTS.md](AGENTS.md)。
