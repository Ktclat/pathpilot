# 0.1.1 验证记录

日期：2026-11-24（香港时间）。本文记录 Material 3 Expressive / Monet 和供应商配置改版；[0.1.0 历史验证](validation-0.1.0.md)单独保留。

## 构建与自动测试

实际执行并成功退出：

```bash
source /workspace/toolchains/pathpilot-env.sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.1-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --verbose artifacts/PathPilot-0.1.1-arm64.apk
```

使用 JDK 17、SDK 37.0、Build Tools 36.0.0、Gradle 9.3.1、AGP 9.1.1、Kotlin 2.2.20。云端引导脚本和 CI SDK 安装脚本均在本环境实际执行成功。SDK XML v4 兼容提示、AGP 旧 Kotlin/DSL 迁移提示和只读系统目录的遥测提示仍可出现在日志中；未抑制这些提示，组合构建成功。

| 验证 | 结果 |
| --- | --- |
| 既有 HTTP 路由 / SSE / Codex OAuth 与转换 | 26 项通过 |
| 供应商校验 / 模型探测 / 自动路由 | 22 项通过 |
| 供应商编辑 ViewModel | 6 项通过 |
| 测试总计 | **54 通过，0 失败，0 跳过** |
| Android Lint | **0 错误、10 条建议** |
| ARM64 APK | 构建成功，ZIP 完整性和 APK v2 签名验证通过 |
| 包身份和 ABI | `dev.pathpilot.app`，仅 `arm64-v8a` |
| APK 版本及系统范围 | `0.1.1` / versionCode 2，minSdk 26，targetSdk 35 |

Lint 剩余建议为目标 SDK（保留 35）、依赖可升级版本及前台共享 WakeLock 超时建议。API 26 导航栏属性问题已修复；主题/图标新增错误均已消除。WakeLock 在停止/销毁前台共享服务时释放，厂商后台省电行为仍需实体设备验证。

新增测试覆盖 HTTPS 地址与 Key 格式、模型列表状态码/超时/取消/重定向/大小限制/错误脱敏、别名冲突与重复路由、分来源草稿凭据隔离、取消后的迟到结果、模型测试失败仍可手动保存，以及重试失败保留已选模型。模型列表测试使用本机 MockWebServer，不发起真实推理。

本地 APK：`artifacts/PathPilot-0.1.1-arm64.apk`。SHA-256：

```text
0d1c75ad5de65bbe9aeda0e4d3f832ab4a0a42dbb3a4500a4279ef8ec7303902
```

远程提交 `2722fb5` 的 [Android ARM64 Actions #4](https://github.com/Ktclat/pathpilot/actions/runs/45402704772) 已成功：测试、Lint、ARM64 包校验与两项产物上传均通过。

## 模拟器验证

截图使用独立 `.capture` 调试变体，在 Android 15 / API 35 x86_64 模拟器运行；正常交付包仍只有 ARM64，仍启用防截屏。测试包与正式包的应用数据和 Keystore 隔离，未录入真实账号或密钥。

同源截图包构建命令：`./gradlew --no-daemon -PuiCapture=true :app:assembleDebug`。截图包 SHA-256：`5fcbbb8964c29f941bc50a2b1ce5d83d0e077cbee295bc4ef5f3e037be96454f`。

已检查的原始 PNG 位于 [`artifacts/screenshots/v0.1.1/`](../artifacts/screenshots/v0.1.1/)。同一 APK 分别在系统蓝色、琥珀色、琥珀深色调色板下渲染，配色变化来自 Android 系统动态 overlay；未修改截图像素。截图不包含真实供应商凭据。

已实测来源选择、DeepSeek 表单、空 Key 提交拦截及错误摘要、键盘打开时底部操作按钮避让、Key 显示/隐藏、选择模型并随供应商保存路由。使用纯测试占位 Key 保存 `QA-Test-Only` 与 `deepseek-chat` 路由，强制停止后重新打开仍保留 1 个供应商和 1 条正确映射的路由。模型读取及错误分支通过 MockWebServer / ViewModel 自动测试；本轮界面验证未向真实供应商发出请求。

无 KVM 的软件模拟器中实际记录到 **2 次约 5 秒 FocusEvent 超时 ANR**：一次发生在系统 Monet 调色板热切换后，一次发生在重启后首次打开供应商编辑器时。主线程样本分别处于文字绘制、`FlowRow → ModelSelection → composeInitial` 首次布局，伴随明显 JIT 与系统 CPU 压力；未见凭据存储、网络或锁等待。第二次事件两分多钟后的同进程样本显示主线程回到消息队列空闲，未持续卡在布局循环。保留这两次真实响应性失败；现有样本不足以确定全部原因或把它们完全归因于环境。正常截图及保存流程通过不代表响应性能通过，仍需 ARM64 实体设备复核。

供应商删除及第二次 ANR 后的编辑交互未完成手工验证；旋转屏幕和跨来源往返也未在此模拟器上补测。测试结束仅清除独立 `dev.pathpilot.app.capture` 包中的占位数据（`pm clear` 成功），未清除正常包；系统调色板和浅色模式已恢复，模拟器停止。

## 验证边界

- 未在实体 ARM64 手机上运行；Android 8–11 的备用色板和导航栏完成编译/Lint 检查，未声称完成这些系统版本的运行验证。
- 未使用真实 ChatGPT/Codex 或 DeepSeek 凭据；真实授权、推理、第三方客户端兼容性和设备间局域网互访仍需账号与设备联调。
- `/models` 探测只验证模型列表接口，不代表推理请求已通过。
- GitHub 开发包使用 debug 签名，长期发布签名尚未配置；远程 APK 签名及哈希可能与本地包不同。
