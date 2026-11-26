# PathPilot

原生 Android AI 路由与共享应用。手机统一管理 Codex 订阅、DeepSeek API 和自定义模型服务，为本机应用和局域网设备提供模型 API 与 MCP 工具入口。

使用 Kotlin、Jetpack Compose 和 Material 3 Expressive。路由服务运行在 Android 前台服务中，日常使用无需 Termux 或命令行。支持 Android 8.0（API 26）及以上，常规安装包仅包含 **ARM64**。

## 功能

| 功能 | 当前实现 |
| --- | --- |
| Codex 订阅 | OpenAI 设备代码登录、令牌刷新、获取订阅模型目录、选择共享模型 |
| 多供应商 | DeepSeek 与自定义 OpenAI 兼容 API；获取模型或手动填写模型 ID |
| 模型管理 | 按供应商分组，设置客户端模型名称、上游模型和启用状态 |
| 模型共享 | 本机与局域网地址、多个网络接口选择、独立 `sk-` 密钥、总开关 |
| 模型协议 | Models、Chat Completions、Responses；具体供应商范围见 [API 文档](docs/api.md) |
| MCP | 远程 Streamable HTTP 连接、获取与选择工具、独立共享服务；实现 MCP 2025-11-25 Tools |
| 统计 | 请求与 Token 趋势、供应商/模型汇总、请求详情；基于本机保留的最近 1,000 条记录 |
| 设置 | 跟随系统/浅色/深色、Android 12+ Monet 取色、简体中文/English、上游代理 |
| 备份 | `.ppbackup` 口令加密导入导出，恢复预览，可选包含 Codex 登录状态 |

模型和 MCP 分别启停、分别鉴权。MCP 工具由支持 MCP 的客户端调用；模型 API 本身不负责自动执行工具。统计使用上游返回的用量，不推算订阅剩余额度或费用。

## 开始使用

从 [GitHub Actions](https://github.com/Ktclat/pathpilot/actions/workflows/android.yml) 下载成功构建的 `PathPilot-arm64-debug` 产物，或从 [Releases](https://github.com/Ktclat/pathpilot/releases) 下载已发布的 APK。

1. 打开「模型」，点击添加供应商，选择 Codex 订阅、DeepSeek 或自定义来源，完成登录或填写 API Key。
2. 进入供应商，点击「获取模型」并保存所选模型；也可手动添加。该页统一管理供应商配置、共享模型名称和开关。
3. 在「共享」页打开模型共享。手机上的其他应用选择「本机」，电脑等设备选择「局域网」，复制对应地址和访问密钥。
4. 在客户端选择受支持的 OpenAI 兼容接口，填写地址、共享密钥和模型名称。

| 客户端位置 | 模型 Base URL 示例 |
| --- | --- |
| 与 PathPilot 在同一手机 | `http://127.0.0.1:8317/v1` |
| 同一局域网的其他设备 | `http://192.168.1.50:8317/v1`，IP 以手机显示为准 |

底部导航为「共享、模型、MCP、设置」。供应商与其模型统一放在「模型」页；统计从模型页右上角进入，也可由最近请求和模型记录跳转。

「MCP」单独管理工具连接。添加连接时填写远程完整 MCP URL，按需填写 Bearer 令牌，获取工具后开启 MCP 共享。客户端使用 `http://<手机地址>:8318/mcp/<连接ID>` 和 MCP 访问密钥；它与模型 `/v1` 地址是两个入口。操作与兼容范围见 [MCP 文档](docs/mcp-roadmap.md)；手机端 GitHub PAT 配置及工具执行分工见 [在手机上使用 MCP](docs/android-mcp.md)。

「设置 → 上游代理」适用于模型请求、模型目录、Codex 授权/刷新与远程 MCP 请求。手机使用 VPN/TUN 时可保留「跟随系统」；局域网设备经手机调用的上游流量也遵循手机的这套网络配置。局域网互访仍取决于 VPN 的局域网放行设置。

备份包含供应商及密钥、模型路由、应用偏好和 MCP 配置，不含请求历史；Codex 登录状态默认不导出。导入先预览，默认保留目标手机的端口、共享密钥和上游代理，恢复不会启动共享。

## 构建

使用 Android Studio 打开仓库，或配置 JDK 17、Android SDK 37.0 和 Build Tools 36.0.0 后运行：

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。Gradle、AGP、Kotlin 和依赖版本由仓库固定；CI 使用 [SDK 安装脚本](scripts/setup-ci-android.sh)。Linux 云环境可先运行：

```sh
bash scripts/bootstrap-cloud.sh
source /workspace/toolchains/pathpilot-env.sh
```

本地与 CI 默认生成 debug 签名开发包。持续覆盖安装需要保持相同签名密钥和递增的 `versionCode`；不同云端 Runner 的临时 debug 密钥可能不同。

## 构建与发布

推送 `main` 或提交 PR 会运行测试、Lint、APK 签名与 ARM64 架构检查。成功构建提供 APK、`SHA256SUMS.txt` 和验证报告。

手动构建在 **Actions → Android ARM64 → Run workflow** 中执行。保持 `publish_prerelease=false` 只构建；需要发布时选择 `main` 并启用该选项。工作流先完成验证，再从同一次构建产物创建 GitHub 预发布版，标签自动使用 `v<APK版本>`，例如 `v0.0.1-a1b2c3d`。

应用版本采用 `0.0.1-<实际提交前7位>`，产物名包含同一版本，例如 `PathPilot-0.0.1-a1b2c3d-arm64-debug.apk`。版本规则与提交要求见 [贡献指南](CONTRIBUTING.md)。当前交付、测试结果和待完成项见 [项目状态](docs/project-status.md)，详细证据见 [验证记录](docs/validation.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [架构](docs/architecture.md) | 模块边界、服务生命周期、配置与数据流 |
| [模型 API](docs/api.md) | 地址、鉴权、接口、供应商兼容与代理 |
| [MCP](docs/mcp-roadmap.md) | 连接与工具共享、协议范围、后续适配 |
| [手机 MCP 与 GitHub](docs/android-mcp.md) | GitHub PAT 接入、客户端配置与手机/远程服务分工 |
| [官方 MCP 来源](docs/mcp-catalog-research.md) | 远程目录、认证方式与本地安装方案 |
| [贡献指南](CONTRIBUTING.md) | 开发、测试、截图、版本和提交流程 |
| [项目状态](docs/project-status.md) | 已验证基线、当前工作和待验证项 |
| [验证记录](docs/validation.md) | 构建、CI、设备测试及限制 |
| [实际运行截图](artifacts/screenshots/README.md) | 各轮模拟器截图与原始记录 |
| [开源复用评估](docs/reuse-assessment.md) | RikkaHub、Magpie 等项目的取舍 |
| [设置与 MCP 参考](docs/settings-mcp-reference.md) | CC Switch、Magpie 与 MCP 一手资料 |

项目使用 [MIT 许可证](LICENSE)。依赖、图标与参考来源见 [第三方说明](docs/third-party.md)；协作约定见 [AGENTS.md](AGENTS.md)。
