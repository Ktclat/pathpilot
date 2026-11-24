# 验证记录

日期：2026-11-24（香港时间）。本文记录本地 `0.1.0` 开发版 APK 和模拟器验证结果；远程 Actions 与 GitHub Release 状态以仓库运行记录和发布页为准。正式长期签名版本尚未发布。

## 构建与核心行为

实际执行：

```bash
bash scripts/bootstrap-cloud.sh
source /workspace/toolchains/pathpilot-env.sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

最终组合命令成功退出。完整 JDK 17、SDK 35、Build Tools 35.0.0、Gradle 8.9 均已安装并用于实际构建；云端安装/启动说明已保存至环境配置草稿，发布仍由用户在环境设置完成。没有验证新任务恢复后的环境。

| 验证 | 结果 |
| --- | --- |
| `RouterServerTest` | 13 通过，0 失败，0 跳过 |
| `ResponseBridgeTest` | 5 通过，0 失败，0 跳过 |
| `CodexOAuthClientTest` | 5 通过，0 失败，0 跳过 |
| `CodexBackendTest` | 3 通过，0 失败，0 跳过 |
| `assembleDebug` | 成功，APK 已生成 |
| `lintDebug` | 0 错误，1 条 WakeLock 超时建议 |
| `apksigner verify --verbose` | APK v2 签名验证通过 |
| APK manifest 检查 | `dev.pathpilot.app`，版本 0.1.0，minSdk 26，targetSdk 35 |

WakeLock 用于用户主动开启的前台共享服务，并在停止/销毁服务时释放；目前没有自动超时关闭共享。这条 Lint 建议未被隐藏。真实厂商系统的长时间省电行为仍需真机验证。

核心测试使用真实本机 HTTP 监听和模拟上游，覆盖访问鉴权、模型映射、SSE 转发与工具增量、JSON 聚合、超时、错误脱敏、密钥轮换、并发限制和客户端断开。测试发现并修复了 NanoHTTPD 的 gzip 缓冲导致 SSE 延迟的问题。测试没有消耗真实模型账号额度。

当前交付 APK：`artifacts/PathPilot-0.1.0-arm64.apk`，同一文件也输出到 `app/build/outputs/apk/debug/app-debug.apk`。

已实际检查 ZIP 原生库目录与 APK `native-code`：仅含 `arm64-v8a`；没有 `armeabi-v7a`、`x86` 或 `x86_64`。正常 debug/release 构建均限制 ARM64。本轮构建、26 项核心测试、Lint 和 APK 签名检查通过；尚未在实体 ARM64 设备上运行。

SHA-256：

```text
b34b80d0a472797063670118300ff98952f2b1ecbc2ebb4d151f22188b01f950
```

## 上一轮 Android 运行验证（通用开发包）

已安装 Android 15 / API 35 x86_64 模拟器并完成启动。当前云机器没有 `/dev/kvm`，使用 Android Emulator 自身提供的 TCG 软件模拟模式，未禁用 APK 或网络校验。

上一轮通用开发包（SHA-256 `5a4bf99edf264aff5f6833b37e79b3ed4af69234e268cacb39e8ac79c6149d00`）已通过 `adb install -r` 安装成功，ActivityTaskManager 于 11:27:56 UTC 记录最终版 `dev.pathpilot.app/.MainActivity` 首屏 `Displayed`；当时未发现 PathPilot 自身的 AndroidRuntime 崩溃。首次 Keystore/配置初始化已移至 IO 线程并显示原生加载界面。这些结果属于上一轮通用开发包，不等同于 ARM64 真机验证。

软件模拟器出现系统、SystemUI、电话/媒体服务无响应，UIAutomator 出现 `Timeout while connecting UiAutomation` 和 `null root node`，因此未完成界面新增供应商、配置路由、前台共享启停及 Android 内 HTTP 访问的整套操作验证。没有在模拟器录入真实账号或调用真实上游，也没有把进程存在等同于功能通过。

## 本轮 UI 截图验证

使用同一份 Compose UI 源码构建独立 `dev.pathpilot.app.capture` 调试应用，在 Android 15 / API 35 x86_64 模拟器上实际安装和运行。仅这个截图测试包开放截图；正常 ARM64 包仍开启防截屏。截图包有独立应用数据目录和 Keystore，未登录或录入真实供应商凭据。

已实际点击共享、供应商、路由、设置标签，打开添加供应商表单并滚动查看连接信息，共得到六张截图。通过 `adb exec-out screencap -p` 保存原始 720×1280 PNG，逐张查看确认是完整运行界面而非加载页、系统弹窗或效果图。图片保存在 `artifacts/screenshots/`，展示尚未配置供应商/路由、共享关闭的初始状态。这验证了原生页面渲染与页面切换，不代表真实账号转发或 ARM64 真机验证。

## 尚需真实环境确认

- 真实 ChatGPT/Codex 账号的设备授权、订阅权限、模型可用性及完整推理；实现依据是官方源码，OAuth 与请求转换通过模拟服务测试。
- 真实 DeepSeek API 调用；没有提供或使用真实 API Key。
- 具体第三方聊天客户端及 Codex CLI 版本的完整端到端兼容性。
- Android 实体手机、其他局域网设备互访、厂商后台省电、长时间并发流式运行。
- Keystore/磁盘写入故障注入以及 OAuth 刷新 Token 轮换中的设备异常恢复。

新供应商和 Magpie 的具体仓库尚未明确；目前提供自定义 OpenAI 兼容 API，未声称未知 Coding Plan 已通过联调。
