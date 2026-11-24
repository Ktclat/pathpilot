# PathPilot 0.1.3 运行截图

2026-11-24 在 Android 15 / API 35、720 × 1280、320 dpi 的 x86_64 模拟器上采集。三张均为 `adb exec-out screencap -p` 原始 PNG，已逐张检查，未生成、拼接或修图。

截图全部来自最终 `PathPilot-0.1.3-ui-capture.apk`，包名 `dev.pathpilot.app.capture`，SHA-256：

`91078550d3ecec8817c6ae81f2cd31bc32d48fe6fdf575622119008d24acea3f`

| 截图 | 实际观察 |
| --- | --- |
| [01-sharing-sk.png](01-sharing-sk.png) | 清空测试包后首次生成的新密钥以 `sk-` 加掩码显示；实际模拟器地址与两个复制入口完整可见 |
| [02-provider-types.png](02-provider-types.png) | ChatGPT / Codex 显示结形品牌标记，DeepSeek 显示蓝色鲸鱼，自定义 API 保留通用图标 |
| [03-deepseek-provider.png](03-deepseek-provider.png) | 使用纯测试占位 API Key 保存的 DeepSeek 供应商，列表正确显示鲸鱼标记 |

打开 DeepSeek 配置时也实际检查了标题中的鲸鱼标记。没有创建 Codex 供应商、登录账号、获取上游模型或调用生成接口；没有开启共享，也没有显示、复制或读取完整共享密钥。本轮没有在设备上验证已有 `pp_` 数据的兼容行为。

主机没有 KVM，使用软件模拟；最终包执行官方 `cmd package compile -m speed -f` 成功，耗时 103 秒。首次启动等待超时并出现 System UI 无响应，关闭系统弹窗、将原 Activity 置回前台一次后完成截图；恢复期间没有杀进程、重启模拟器或重装 APK。最终三张均不含系统弹窗，首页已替换为状态栏和网络地址恢复后的原图。

自最终包首次启动起的日志中记录到 2 次 `com.android.systemui` ANR，未发现 capture 包 ANR、`FATAL EXCEPTION`、VectorDrawable / 路径解析异常；最终 crash buffer 为空。这是预编译软件模拟环境下的画面验证，不是冷启动性能测试。

完成后仅清除了 capture 测试数据并停止本次模拟器，AVD 和截图保留，正式应用数据未改动。工作区诊断摘要：`/tmp/pathpilot-v013-diagnostics-summary.json`；预编译记录：`/tmp/pathpilot-v013-final-precompile.log`。
