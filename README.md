# PathPilot

原生 Android AI 路由与局域网共享应用。手机管理 Codex 订阅授权、DeepSeek API 和自定义 OpenAI 兼容供应商，其他设备使用统一地址、独立密钥与模型别名调用。

使用 Kotlin、Jetpack Compose、Android 前台服务和轻量 JVM HTTP 路由核心；日常使用不需要 Termux、电脑常驻或命令行。

## 当前能力

- **Codex 订阅**：OpenAI 官方设备代码授权、浏览器登录、令牌刷新；不是用 OpenAI API Key 替代订阅。账号必须拥有相应 Codex 权限，模型 ID 按账号实际可用范围填写。
- **DeepSeek / 自定义供应商**：分步添加、HTTPS 地址与密钥校验、可选读取模型列表、手动填写模型、随供应商一起保存路由、启停及删除。
- **原生界面**：官方 Material 3 Expressive 主题和组件；Android 12+ 跟随系统 Monet 动态取色与明暗主题，Android 8–11 使用统一备用配色。应用图标、导航和操作图标使用一致样式，支持 Android 13+ 主题图标。
- **模型路由**：客户端别名 → 供应商 → 上游模型 ID；支持运行中更新配置。
- **局域网共享**：用户主动开启的前台服务、常驻通知及停止操作，显示 IPv4 地址、端口、独立随机访问密钥。
- **API**：`GET /v1/models`、`POST /v1/chat/completions`、`POST /v1/responses`。Codex 支持聊天到 Responses 的转换；DeepSeek 仅使用 Chat Completions。详细边界见 [接口文档](docs/api.md)。
- **隐私**：Android Keystore AES-GCM 加密整个配置，原子写入，禁用备份；记录仅包含模型、HTTP 状态和耗时。局域网请求不携带上游凭据。

## 在手机上使用

1. 安装构建生成的 ARM64 `app-debug.apk`，打开 PathPilot（64 位 ARM Android 8.0 / API 26 或更新版本）。本项目常规构建只打包 `arm64-v8a`，不提供 ARM32、x86 或 x64 安装包。
2. 在「供应商」中添加 Codex 订阅，打开 OpenAI 官方页面并输入设备代码。授权完成后回到应用。若账号设置要求，先在 ChatGPT 安全设置中启用设备代码登录。
3. 或选择 DeepSeek，填写 API Key，保留默认 `https://api.deepseek.com/v1`。自定义服务填写其真实 HTTPS API 前缀。可点击「测试连接并获取模型」，选择需要共享的模型；也可直接手动填写。测试只读取模型列表，不发起生成请求。
4. 保存供应商时会为所选模型创建路由，重复模型不会重复创建，重名别名自动加后缀。也可在「路由」中单独添加共享别名和上游模型 ID，例如 `deepseek-chat` → DeepSeek → `deepseek-chat`。Codex 模型需填写账号实际支持的 ID，应用不预设已获授权的模型目录。
5. 连接可信 Wi-Fi，开启「共享」。在其他设备中填入手机显示的 API 地址、共享密钥和别名。
6. 结束使用时停止共享；更改监听端口需先停止服务，密钥重置立即影响后续请求。

手机局域网入口使用 HTTP，上游使用 HTTPS。请在可信网络使用；路由器的客户端隔离会阻止设备互访。部分 Android 厂商的省电策略需要允许应用后台运行。普通聊天客户端可使用 Chat Completions，Coding 客户端优先使用 Responses；WebSocket、Realtime、文件上传、后台响应及完整 Codex CLI 端到端兼容性不在首版验证范围。

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

本轮 ARM64 交付副本：`artifacts/PathPilot-0.1.1-arm64.apk`。已检查 APK 中的原生库仅有 `lib/arm64-v8a/`。

### UI 运行截图

普通安装包保持防截屏。云端截图使用独立测试开关 `./gradlew -PuiCapture=true :app:assembleDebug`，临时生成供当前 x86_64 Android 模拟器使用的 `dev.pathpilot.app.capture` 调试应用，并允许截屏。它与正常应用的数据及 Keystore 隔离，不用于真实账号或交付；该开关禁止 Release 任务。截完后执行不带该参数的常规构建，即恢复 ARM64 和防截屏配置。

截图直接来自 Android 模拟器运行中的界面，不是设计稿；新版图片位于 `artifacts/screenshots/v0.1.1/`，首版截图保留在 `artifacts/screenshots/`。实体 ARM64 手机运行和网络联调仍单独验证。

测试使用本机 MockWebServer 和真实 HTTP 路由监听，覆盖鉴权、路由、SSE、订阅请求适配及 OAuth 刷新。它们不会访问付费推理服务。实际测试结果与尚未完成的设备/账号验证见 [验证记录](docs/validation.md)。

## GitHub Actions 与开发预发布

进入仓库 **Actions → Android ARM64 → Run workflow**，选择 `main`：

- 只验证构建：保持 `publish_prerelease=false`。成功后在该运行的 Artifacts 下载 `PathPilot-arm64-debug`，其中包含 ARM64 APK 和 `SHA256SUMS.txt`。
- 验证成功后发布：设置 `publish_prerelease=true`，填写新的 `release_tag`，例如 `v0.1.1-dev.1`。同一次运行先完成测试、Lint、签名与架构检查，再用其构建产物发布 GitHub 预发布版；失败不会发布。

推送到 `main` 或提交 PR 只做构建检查，不会发布。也可以在 `main` 的提交上创建并推送 `v0.1.1-dev.1` 形式的新标签，自动触发「构建 → 检查 → 预发布」。此方式不要求开发机器调用 GitHub API。发布会校验标签确实属于 `main` 的历史且版本匹配 APK；手动触发仍仅允许选择 `main`。已有 Release 不会被覆盖，标签不会被移动。上传失败时可能留下未公开的草稿，应检查运行日志后处理，不要删除已发布版本重试。

当前使用 **debug 签名**：不同 GitHub Runner 的签名可能不同，后续 APK 不保证可覆盖安装，卸载会清除本机配置和授权。开发预发布用于测试；正式发布前应配置长期签名密钥。本流程不会在仓库中保存私钥或真实供应商凭据。

## 结构与扩展

```text
app/   Compose 界面、Android Keystore、前台服务与通知
core/  HTTP 路由、数据模型、Codex OAuth、Responses/Chat 转换、JVM 测试
docs/  一手资料、复用评估、兼容性与验证记录
```

目前的路由按模型别名明确选择单个上游，不做隐式回退或重复发送生成请求。兼容 OpenAI API 的新服务可直接添加；不同认证或协议的 Coding Plan 需增加专用适配器，不能仅凭服务名称认定兼容。后续具体 zcode 服务、Magpie 仓库地址和目标客户端明确后，可按相同边界扩展。

界面和供应商流程：[设计与行为说明](docs/ui-and-providers.md)。

研究与设计依据：[开源复用评估](docs/reuse-assessment.md)、[Codex 官方源码核查](docs/codex-research.md)。未复制 RikkaHub 的 AGPL 实现。项目保留仓库原有 MIT 许可证；依赖及参考说明见 [第三方说明](docs/third-party.md)。
