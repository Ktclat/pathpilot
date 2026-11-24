# 开源实现评估

调查日期：2026-11-24。下列结论来自公开仓库的固定提交和源代码检查，不代表已使用真实订阅完成请求，也不代表已在 Android 真机上验证这些上游项目。

## 采用方案

PathPilot 采用独立 Kotlin / Jetpack Compose 原生界面、Android 前台服务、JVM HTTP 路由核心。首版围绕 Codex 订阅、DeepSeek 和自定义 OpenAI 协议供应商实现最小完整路径。账号和路由配置由原生界面管理，不依赖 Termux、网页套壳或外部电脑运行代理。

这是当前范围下的工程判断：纯 JVM 核心可以直接做 HTTP/流式集成测试，也能由 Android Service 管理；不必先完成 Go/Rust 到 Android 的交叉编译、桥接和生命周期适配。CLIProxyAPI 的嵌入路线保留为未来供应商数量显著增加时的备选，不将其表述为技术不可行。

## 已核实的参考项目

| 项目 | 核实结果 | 适合借鉴或复用的部分 | 本次选择 |
| --- | --- | --- | --- |
| RikkaHub | Kotlin / Compose 原生 Android；AGPL-3.0；当前源码已经有 ChatGPT/Codex 订阅 provider 和 OAuth，不只是 API Key 配置 | 移动端供应商编辑、账号与凭据分层、订阅模型目录、Responses 请求适配 | 参考交互与协议边界；不直接复制其 AGPL 实现到本项目 |
| CC Switch | Tauri 2 + Rust；MIT；有 Axum 本地代理、Codex 设备授权、令牌刷新和模型转换 | 供应商与账号绑定、代理启停、明确区分协议、设备授权状态机 | 参考配置与路由设计；不移植桌面 UI 或桌面配置文件接管逻辑 |
| CLIProxyAPI | Go 模块；MIT；有可嵌入的 `sdk/cliproxy.Builder`，以及认证、路由和协议转换模块 | 未来可以用 JNI / gomobile 边界包装成 Android 内核；也可参考协议测试案例 | 首版不引入完整 Go 核心；当前发布构建未发现 Android APK/AAR 目标，Android 集成尚需独立验证 |
| Magpie | 用户没有提供仓库 URL；当前资料不足以唯一确认具体项目 | 产品需求已吸收其“订阅/API 来源统一管理、模型路由、共享入口”意图 | 不猜测仓库、许可证或已有能力；后续给出 URL 后可补评估，不阻塞实现 |

### RikkaHub 一手依据

固定提交：`2435dbdf4690fe26d424b8d1ab778bcde9c6cd75`。

- [README：原生 Android、Compose、供应商配置](https://github.com/rikkahub/rikkahub/blob/2435dbdf4690fe26d424b8d1ab778bcde9c6cd75/README.md)
- [LICENSE：AGPL-3.0](https://github.com/rikkahub/rikkahub/blob/2435dbdf4690fe26d424b8d1ab778bcde9c6cd75/LICENSE)
- [ChatGPTProvider.kt：Codex 后端、模型目录与 Responses 适配](https://github.com/rikkahub/rikkahub/blob/2435dbdf4690fe26d424b8d1ab778bcde9c6cd75/ai/src/main/java/me/rerere/ai/provider/providers/chatgpt/ChatGPTProvider.kt)
- [ChatGPTAuth.kt：PKCE 登录、固定回调、刷新和凭据转换](https://github.com/rikkahub/rikkahub/blob/2435dbdf4690fe26d424b8d1ab778bcde9c6cd75/app/src/main/java/me/rerere/rikkahub/data/ai/account/ChatGPTAuth.kt)

重要修正：不能再把 RikkaHub 描述为“只有 OpenAI API Key、没有订阅接入”。检查到的版本已使用 `https://chatgpt.com/backend-api/codex`、`ChatGPT-Account-ID` 和 `originator`；模型目录带 `client_version`；请求侧处理 Codex 的流式和参数差异。它的源码也提供浏览器 PKCE 回调方案，说明原生 Android 承载订阅认证是有现成实现依据的。是否直接 fork RikkaHub 是产品范围与许可证选择，本项目当前不需要先 fork 整个聊天客户端。

### CC Switch 一手依据

固定提交：`e6fd4accd257406bf9141baa402caaa78f975ba1`。

- [Cargo.toml：Tauri、Axum、Reqwest](https://github.com/farion1231/cc-switch/blob/e6fd4accd257406bf9141baa402caaa78f975ba1/src-tauri/Cargo.toml)
- [LICENSE：MIT](https://github.com/farion1231/cc-switch/blob/e6fd4accd257406bf9141baa402caaa78f975ba1/LICENSE)
- [codex_oauth_auth.rs：设备授权、账号绑定、刷新](https://github.com/farion1231/cc-switch/blob/e6fd4accd257406bf9141baa402caaa78f975ba1/src-tauri/src/proxy/providers/codex_oauth_auth.rs)
- [server.rs：实际路由注册](https://github.com/farion1231/cc-switch/blob/e6fd4accd257406bf9141baa402caaa78f975ba1/src-tauri/src/proxy/server.rs)

源码注册了 `/v1/models`、`/v1/chat/completions`、`/v1/responses` 等路由；Codex 授权明确经过 usercode → token 轮询 → authorization_code + code_verifier 换令牌。它提供了可参考的协议实现，但 Tauri 桌面 UI 与本项目的 Android 原生 UI 是不同交付形态，不能将桌面可用直接等同于 Android 可用。

### CLIProxyAPI 一手依据

固定提交：`85225381d89915595598ed13766cd79890c03a84`。

- [LICENSE：MIT](https://github.com/router-for-me/CLIProxyAPI/blob/85225381d89915595598ed13766cd79890c03a84/LICENSE)
- [go.mod：Go 模块与依赖](https://github.com/router-for-me/CLIProxyAPI/blob/85225381d89915595598ed13766cd79890c03a84/go.mod)
- [SDK Builder：可嵌入服务及生命周期 hooks](https://github.com/router-for-me/CLIProxyAPI/blob/85225381d89915595598ed13766cd79890c03a84/sdk/cliproxy/builder.go)
- [server_routes.go：鉴权中间件与 API 路由](https://github.com/router-for-me/CLIProxyAPI/blob/85225381d89915595598ed13766cd79890c03a84/internal/api/server_routes.go)
- [codex_device.go：设备授权实现](https://github.com/router-for-me/CLIProxyAPI/blob/85225381d89915595598ed13766cd79890c03a84/sdk/auth/codex_device.go)
- [release.yaml：发布构建目标](https://github.com/router-for-me/CLIProxyAPI/blob/85225381d89915595598ed13766cd79890c03a84/.github/workflows/release.yaml)

源码对 `/v1` 路由组挂载鉴权，注册 Models、Chat Completions、Responses，以及独立的 Responses WebSocket、compact 等能力。它的能力范围比首版 PathPilot 更大；不能因为 PathPilot 实现了同名 HTTP 路由就声称获得全部 CLIProxyAPI 或 Codex CLI 兼容性。若以后嵌入，应固定版本、保留 MIT notice，单独验证 Android ABI、文件访问、内存占用、网络切换和进程恢复。

## 对接口兼容性的具体决策

1. **Chat Completions 与 Responses 分开验证。** DeepSeek 的标准聊天接口走 Chat Completions；Codex 订阅上游走 Codex Responses。对不支持的协议返回可解释错误，不能静默将失败说成“兼容 OpenAI”。
2. **订阅认证独立于 API Key。** Codex 使用 OAuth 凭据并维护刷新状态，DeepSeek/其他 API 服务使用各自的 API Key；局域网客户端只拿本地独立密钥。
3. **模型别名是明确的路由规则。** 客户端请求公开别名，由手机解析成特定供应商和上游模型，避免不透明自动重试导致重复生成或额外消费。
4. **扩展来源以协议适配器为边界。** 兼容现有协议的服务可配置自定义 Base URL；有特殊登录或私有协议的 coding plan 需增加对应适配器。未明确的 zcode 服务身份不凭名称臆造登录支持。
5. **真实验证分层。** 本地 HTTP/流式测试证明路由逻辑；真实账号测试证明订阅权限和上游协议；手机安装与局域网设备测试证明 Android 生命周期和共享链路。这三类结果需要分别记录。
