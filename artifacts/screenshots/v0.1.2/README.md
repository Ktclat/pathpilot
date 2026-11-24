# PathPilot 0.1.2 运行截图与设备验证

采集日期：2026-11-24。以下 PNG 均由实际 Android App 运行后通过 `adb exec-out screencap -p` 获取，逐张检查；未生成、拼接或修改图像。界面使用系统蓝色动态配色，访问密钥始终保持掩码。

## 截图来源

- Android 15 / API 35，x86_64，720 × 1280，320 dpi。
- 独立测试包：`dev.pathpilot.app.capture`。正式交付的 ARM64 APK 不在此模拟器上运行。
- 主机没有 `/dev/kvm`，使用软件模拟。两次安装后均执行 Android 官方 `cmd package compile -m speed -f dev.pathpilot.app.capture`，结果为 `Success`（分别耗时 38.6 秒、52.2 秒）。这是预编译环境中的功能验证，不代表未经预热的冷启动性能。
- 构建 A SHA-256：`2a502425ab099d6c81e91e9e48240e1c75339ad39c4fdb6a80bc05d37ee63109`。
- 最终构建 B SHA-256：`e34a4193eccc1f7c45e4139150baaecc49b9263db34d588dd743dbf08939569f`，对应 `artifacts/PathPilot-0.1.2-ui-capture.apk`。

截图 01 来自最终构建 B；02–08 来自构建 A，其所示页面在 B 中没有改动。B 包含首页高度和直达添加入口的修正，以及 HTTP keep-alive 修复。

| 截图 | 实际画面 | 构建 |
| --- | --- | --- |
| [01-sharing-empty.png](01-sharing-empty.png) | 清理后的共享首页；0 供应商、0 路由，地址和掩码密钥卡完整显示 | B |
| [02-provider-types.png](02-provider-types.png) | 三种来源全部在首屏；点击类型进入配置 | A |
| [03-deepseek-form.png](03-deepseek-form.png) | 名称、API Key、默认地址及模型入口同屏 | A |
| [04-provider-models.png](04-provider-models.png) | 勾选 deepseek-chat，保留手动模型输入和获取按钮 | A |
| [05-settings.png](05-settings.png) | 端口、上游代理、密钥重置、后台运行和使用说明 | A |
| [06-upstream-proxy.png](06-upstream-proxy.png) | SOCKS5 配置弹窗；测试地址为 127.0.0.1:7890 | A |
| [07-saved-provider.png](07-saved-provider.png) | 纯测试供应商 qa-test-only，已关联一条路由 | A |
| [08-saved-route.png](08-saved-route.png) | deepseek-chat 映射到测试供应商 | A |

## 实际操作结果

| 操作 | 结果与范围 |
| --- | --- |
| 空 DeepSeek API Key 提交 | A：被拦截，错误显示在字段下方并自动聚焦；键盘弹出时保存按钮仍可见 |
| 保存供应商与模型 | A：使用纯占位值 `not-a-real-test-key`，保存 qa-test-only 并通过建议项创建 deepseek-chat 路由；没有点击获取模型 |
| 配置持久化 | B：覆盖安装、重新启动后，供应商名称、掩码凭据、路由和 SOCKS5 设置仍保留 |
| 代理选项 | A：SOCKS5 127.0.0.1:7890 保存后在设置页正确显示；B：通过 UI 恢复跟随系统。未连接真实代理服务器 |
| 通知权限 | A：首次开启共享时出现系统通知权限对话框，选择允许；B 沿用该授权 |
| 最终包启动共享 | B：UI 显示共享中，通过 `adb forward tcp:18317 tcp:8317` 请求 `/health`，返回 HTTP 200 与 `{"status":"ok"}` |
| 最终包停止共享 | B：点击停止后，设备 `ss -ltn` 中不再有 8317 监听，转发地址的 HTTP 请求断开 |
| 删除测试供应商 | B：点击删除并确认，供应商列表恢复为空；首页显示 0 供应商、0 路由，关联路由一并删除 |
| 首页直接添加入口 | B：在空首页点击添加供应商后直接显示三种来源，没有再经过供应商空列表；检查后关闭编辑 |

`10.0.2.15` 是本次模拟器的实际地址。HTTP 验证通过 ADB 转发完成，不等同于手机与另一台局域网设备之间的实测。没有进行真实 Codex 授权、上游模型列表获取或模型生成请求，也没有读取、显示或复制局域网访问密钥。

## 运行记录与清理

系统启动和初次打开阶段出现过 System UI 无响应弹窗，关闭后继续。自本轮首个 0.1.2 启动起保存的 ActivityManager 日志包含 1 次 `com.android.systemui` ANR，未发现 `dev.pathpilot.app.capture` ANR；最终 crash buffer 为空。上述截图均不含系统弹窗，这些结果不用于推断真机性能。

服务已停止，测试供应商和路由已通过应用删除，代理恢复跟随系统，临时 ADB 转发已移除。本次模拟器已停止，AVD 数据及截图保留；正式应用包数据未改动。

本次工作区中的详细记录：`/tmp/pathpilot-v012-final-precompile.log`、`/tmp/pathpilot-v012-final-launch.log`、`/tmp/pathpilot-v012-final-health-start.json`、`/tmp/pathpilot-v012-final-health-stop.json`、`/tmp/pathpilot-v012-final-runtime-events.txt`。
