# 0.1.2 验证记录

日期：2026-11-24（UTC）。本轮精简四个主页面和供应商表单，新增上游代理，调整共享服务的 IO 执行，并移除自定义供应商的 HTTPS/必填 Key 限制。[0.1.1 历史记录](validation-0.1.1.md)保留。

## 构建与自动测试

实际执行并成功退出：

```bash
source /workspace/toolchains/pathpilot-env.sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.2-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.2-arm64.apk
```

| 验证 | 结果 |
| --- | --- |
| 核心协议、供应商、OAuth 与代理 | 62 项通过 |
| 供应商编辑 ViewModel | 9 项通过 |
| 合计 | **71 通过，0 失败，0 跳过** |
| Android Lint | **0 错误，11 条警告** |
| 包身份和 ABI | `dev.pathpilot.app`，仅 `arm64-v8a` |
| 版本及系统范围 | `0.1.2` / versionCode 3，minSdk 26，targetSdk 35 |
| APK | ZIP、签名和 ABI 检查通过 |
| Actions 配置 | actionlint 通过 |

工具链：JDK 17、SDK 37.0、Build Tools 36.0.0、Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.20。Lint 剩余项为目标 SDK、依赖版本，以及 WakeLock 释放路径与超时提示。服务在 IO 协程的 `finally` 中释放监听和锁；未抑制相关检查，实体设备后台运行仍待验证。

代码提交 `cd96140` 的 [Android ARM64 Actions](https://github.com/Ktclat/pathpilot/actions/runs/45425311475) 已成功，包含测试/Lint、APK 签名/ABI 校验、APK 与验证报告上传。

本轮回归覆盖 HTTP CONNECT / SOCKS5 与系统代理选择、TLS 上游请求、OAuth 和模型获取的代理应用、代理切换后取消旧探测、HTTP/无 Key 兼容服务、刷新响应缺少 access_token、拒绝请求后 HTTP 连接复用。修复了早返回未消费 POST 请求体导致下一请求解析失败的问题；已完整消费的请求仍可复用连接。网络测试使用本机模拟上游和代理。

本地交付包：`artifacts/PathPilot-0.1.2-arm64.apk`。SHA-256：

```text
af97abef9a302d36df7e923cc0923f7927bf5df7924ca5d166589e9bf97e35a4
```

## 模拟器与截图

原始截图和操作结果见 [0.1.2 截图记录](../artifacts/screenshots/v0.1.2/README.md)。运行环境为 Android 15 / API 35 x86_64 软件模拟器，无 KVM。独立 `dev.pathpilot.app.capture` 包用于此环境，常规交付包仍只有 ARM64。正常应用已允许截图；密钥默认隐藏。

截图包通过 `./gradlew --no-daemon -PuiCapture=true :app:assembleDebug` 构建，并使用 `cmd package compile -m speed` 预编译后交互。该条件不代表未预编译冷启动性能。截图保留原始像素；只录入占位供应商数据。

已在最终截图包实测配置跨升级保留，以及「开启共享 → `/health` 返回 200 → 停止共享后 8317 端口关闭」。接口通过 ADB 端口转发访问，这项检查验证服务生命周期；设备间 Wi-Fi 互访另需联调。表单空 Key 定位、模型与供应商一同保存、代理配置保存等界面检查见截图记录。

真实账号推理、实体手机的 VPN 出口 IP 和设备间 Wi-Fi 互访尚待联调。0.1.1 的应用 ANR 保留在历史记录中，根因与实体设备响应性仍待复核。开发包使用 debug 签名，GitHub 构建产物的签名与哈希可能和本地不同。
