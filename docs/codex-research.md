# Codex 订阅接入：依据、实现和验证边界

本应用接入的是 ChatGPT 账号的 Codex 订阅后端，不把 OpenAI API Key 当成订阅接入。手机打开官方授权页面，获得的 OAuth 凭据保存在 Android 端；局域网客户端只持有独立共享密钥。

## 一手依据

本次核查 `openai/codex` 的提交为 `32119bac269b881d512b82df74af285e8c60adc2`，许可证 Apache-2.0。以下链接固定到实际检查的源码版本：

- [设备授权与轮询协议](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/login/src/device_code_auth.rs)
- [OAuth 授权码及刷新请求的编码](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/login/src/oauth/client.rs)
- [公开客户端 ID、刷新策略](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/login/src/auth/manager.rs)
- [OAuth issuer 与授权码交换](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/login/src/server.rs)
- [Codex 订阅后端地址](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/model-provider-info/src/lib.rs)
- [Responses 请求结构](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/codex-api/src/common.rs)
- [请求构造：stream、store、encrypted_content](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/core/src/client.rs)
- [originator 协议标识](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/login/src/auth/default_client.rs)

本项目实现为独立 Kotlin 代码，未打包官方 Rust CLI，也未复制 RikkaHub 的 AGPL 实现。开源方案的比较见 [reuse-assessment.md](reuse-assessment.md)。

## 手机上的完整认证流程

1. POST `https://auth.openai.com/api/accounts/deviceauth/usercode`，JSON 参数 `client_id`。
2. 在系统浏览器打开 `https://auth.openai.com/codex/device`，由用户登录并填写设备码。
3. 按服务端返回的间隔 POST `/api/accounts/deviceauth/token`，参数为 `device_auth_id` 和 `user_code`。官方实现将 403、404 视为等待授权；401 不是等待状态。15 分钟超时后需重新开始。
4. 成功响应返回 `authorization_code` 和服务端生成的 PKCE `code_verifier`；向 `/oauth/token` 提交 form 编码的 `authorization_code` grant，redirect URI 为 `https://auth.openai.com/deviceauth/callback`。
5. 到期前刷新：向 `/oauth/token` 提交 JSON 编码的 `refresh_token` grant。刷新响应可能省略 `id_token` 或 `refresh_token`，此时保留原值；旋转后的刷新令牌必须持久化。

公开 OAuth 客户端 ID 是 `app_EMoamEEZ73f0CkXaXp7hrann`，它不是用户密钥。该设备流程不需要 Android 监听 1455 回调端口；浏览器 PKCE loopback 是将来可增加的另一种登录方式，目前未实现。

`CodexOAuthClient` 负责单次同步网络操作，UI 在 IO dispatcher 上运行并控制轮询及取消。所有认证请求禁止重定向和自动网络重试，避免凭据随重定向转发、或一次性授权码被重复消费。错误只呈现状态及操作名称，不显示服务端可能回显凭据的响应内容。

登录由 Activity 作用域的 `CodexLoginViewModel` 管理，屏幕旋转与 Activity 配置重建会保留设备授权轮询和待保存令牌；只有供应商 ID 进入界面恢复状态，令牌不写入 Bundle 或 SavedState。加密保存失败可以直接重试保存。用户关闭窗口会取消未完成的授权流程。系统终止进程后，尚未加密保存的登录需要重新授权；已保存账号从本机凭据库恢复。

## 转发边界

订阅请求固定发往 `https://chatgpt.com/backend-api/codex/responses`，携带 Bearer access token、`ChatGPT-Account-ID` 与 `originator: codex_cli_rs`。User-Agent 标明 PathPilot。不能通过修改自定义供应商地址把 Codex token 发给任意服务器。

当前适配实现：

- 将字符串 `input` 转为消息数组，将 `instructions` 转为开头的 developer 消息；保留工具、推理和图像等已提供的 Responses 输入结构。
- 上游强制 `stream: true`、`store: false`，并请求 `reasoning.encrypted_content` 供客户端保存多轮推理上下文。局域网非流式返回需要路由层收集 `response.completed`；不是把 SSE 原样伪装成 JSON。
- 明确拒绝 `previous_response_id` 和后台任务；HTTP 转发客户端需要提交完整历史。未知字段保留，让上游返回实际支持情况；不静默删掉调用者的要求。
- JWT 只解析账号和到期提示，不宣称本地验证签名；真正的授权由 HTTPS OAuth 服务和每次模型请求的上游验证。
- 未实现 WebSocket 会话、服务端持久会话、文件上传和后台模型目录同步。0.1.6 可在登录后或供应商页主动获取订阅目录并选择模型，接口依据见下文。

官方代码证明协议的存在与具体格式，不能单独证明每个 ChatGPT 账号都已开通相同模型、额度或设备登录能力。若请求设备码返回 404，应用提示该服务器未开放设备登录；真实授权和推理仍需在用户账号上验证。

## 可检查的验证

`core/src/test/kotlin/dev/pathpilot/core/codex` 使用本地 MockWebServer 验证以下实际 HTTP 行为：

- 请求设备码、等待授权、PKCE 授权码交换的请求路径、JSON/form 编码和字段；
- 到期终止、401 失败和错误内容脱敏；
- 刷新保留省略字段、账号变更拒绝、过期时间计算；
- Responses 输入转换、固定认证地址、流式/无存储参数与工具结构保留。

本次 `:core:test` 已执行这两个测试类：OAuth 5 项、Backend 3 项，共 8 项通过，失败、错误和跳过均为 0。完整工程的最终验证结果以根目录 README 和交付报告为准。

这些测试不等于真实 ChatGPT 账号已登录或付费推理已成功。当前实现不内置任何真实账号、API key 或测试凭据。

云端若需要执行真实验证，出站网络需允许 `auth.openai.com` 和 `chatgpt.com`；安装到手机后使用手机自身网络。没有真实账号授权时仍可以编译 APK、运行本地路由和认证协议测试。

## 订阅模型目录（2026-11-25 补充）

Codex 订阅提供独立的模型目录接口；它不是 OpenAI API Key 使用的 `/v1/models`。本次以原先固定的官方提交 `32119bac269b881d512b82df74af285e8c60adc2` 核查协议，并通过官方 Git tag 核实稳定版本 `rust-v0.172.0` 对应提交 `3d86e43b075adeba2193ec5c22ac92f55afd82e6`。两处实现相符，许可证仍为 Apache-2.0。

- [ModelsClient 的 GET 路径及 client_version 查询参数](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/codex-api/src/endpoint/models.rs#L33)：请求 `https://chatgpt.com/backend-api/codex/models?client_version=0.172.0`。版本值依据[已核查发布版的 Cargo.toml](https://github.com/openai/codex/blob/3d86e43b075adeba2193ec5c22ac92f55afd82e6/codex-rs/Cargo.toml#L176)；[官方取包版本的 major.minor.patch](https://github.com/openai/codex/blob/3d86e43b075adeba2193ec5c22ac92f55afd82e6/codex-rs/models-manager/src/lib.rs#L26)。这里是目录兼容版本，不是 PathPilot 应用版本。
- [目录请求复用账号认证](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/model-provider/src/models_endpoint.rs#L154)；[Bearer header 实现](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/model-provider/src/bearer_auth_provider.rs#L34)携带 access token 与 `ChatGPT-Account-ID`。PathPilot 同时发送 `originator: codex_cli_rs`、`Accept: application/json` 和标明 PathPilot 的 User-Agent。
- 响应是 `{"models":[...]}`，调用模型名取 `slug`，不把 `display_name` 当 ID。依据[ModelInfo 与 ModelsResponse](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/protocol/src/openai_models.rs#L419)，本实现读取已使用字段并忽略其余模型能力描述。
- 官方[将 visibility=list 映射为 show_in_picker](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/protocol/src/openai_models.rs#L1120)，[默认模型列表排除隐藏项](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/app-server/src/models.rs#L8)。本实现只返回 `list`，排除 `hide`、`none`；去重并保留服务返回顺序。不认识或缺失 `slug`/`visibility` 的响应报格式错误，不伪装成空目录。
- **订阅模式不按 supported_in_api 过滤。** 官方[认证过滤条件](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/protocol/src/openai_models.rs#L1160)是 `chatgpt_mode || supported_in_api`，[对应测试](https://github.com/openai/codex/blob/32119bac269b881d512b82df74af285e8c60adc2/codex-rs/models-manager/src/manager_tests.rs#L1877)明确保留 ChatGPT 账号的 `supported_in_api=false` 模型。静态数据虽含 `available_in_plans`，官方 ModelInfo 不读取它；本实现也不猜测用户套餐并二次筛选。

`CodexModelsClient.probe(accessToken, accountId)` 同步读取当前账号目录，返回与其他供应商相同的 `ProviderProbeResult`，通过 `ProviderProbeException` 区分登录过期、无读取权限、限流、超时、目录不支持和格式错误。生产端点固定，不能通过供应商 URL 将订阅 token 发往其他地址；使用全局出站代理设置，禁止重定向与自动网络重试。`cancel()` 会取消正在执行和尚未执行的请求；取消后需新建实例。调用方先解析并按需刷新本机已有账号凭据，再在 IO dispatcher 发起目录请求。

此功能是用户主动获取模型，不是后台自动同步或模型调用成功的判定；不发起推理、不使用硬编码模型列表补成功结果。真实空目录正常返回空结果；读取失败保留可辨认的错误。Codex 响应限制为 4 MiB、最多 500 个不同模型 ID；普通供应商的 `/models` 仍为 1 MiB。

该区别依据实际目录体积：上述两个固定提交的 [`models-manager/models.json`](https://github.com/openai/codex/blob/3d86e43b075adeba2193ec5c22ac92f55afd82e6/codex-rs/models-manager/models.json) 均为 **667,893 字节、14 个模型，其中 11 个 visibility=list**。按无缩进 UTF-8 JSON 重序列化测量，最大单模型约 65,982 字节，`model_messages` 约 63,327 字节；该字段包含模板、持久指令、工具及确认策略等长文本。4 MiB 为这种目录提供有界余量，不将只含短 ID 的普通目录上限直接套用。新增合成 fixture 沿用官方嵌套字段结构，含 24 个模型、每模型约 60 KiB 指令文本；测试明确验证超过 1 MiB 的 Content-Length 和 chunked 响应可解析，超过 4 MiB 仍拒绝。合成文本不含真实账号或复制的官方提示内容。

新增 `CodexModelsClientTest` 覆盖固定端点和版本、账号认证、订阅专属模型保留、隐藏模型排除、空目录、HTTP 错误脱敏、错误结构、重定向、取消、超时、响应边界及出站代理。测试使用本地 MockWebServer；该节不将测试编写等同于已运行通过，最终执行结果以交付报告为准，也不声明真实账号目录已验证。
