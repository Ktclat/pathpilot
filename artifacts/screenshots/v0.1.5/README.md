# v0.1.5 实际 Android 截图与验证

2026-11-24，在 Android 15 / API 35 x86_64 模拟器运行独立测试包 `dev.pathpilot.app.capture`。以下 720×1280 PNG 均来自 `adb exec-out screencap -p`，逐张查看过，未编辑像素或向 App 注入展示数据。

截图包：`PathPilot-0.1.5-ui-capture.apk`，SHA-256：

```text
bf0316db0f6f0d405f4b8271d45c0df2710147fda21b058074ca4678410ccc1b
```

| 截图 | 实际画面 |
| --- | --- |
| [01-local-app.png](01-local-app.png) | 默认选中“本机应用”；没有局域网测试接口时，仍显示 `127.0.0.1:8317/v1`、接入说明和密钥掩码。 |
| [02-lan-devices.png](02-lan-devices.png) | 共享开启后切换“局域网设备”；两个地址可选，当前选中 `192.168.50.2`。 |
| [03-protocol-overview.png](03-protocol-overview.png) | 接入说明顶部：本机用途、HTTP / JSON / SSE / Bearer、Base URL 和完整 Chat Completions 路径。 |
| [04-protocol-endpoints.png](04-protocol-endpoints.png) | 实际滚动到底：Chat、Responses、models 完整端点，各上游支持说明及共用服务说明。长 URL 自然换行。 |

## 功能结果

可复核的无凭据记录见 [runtime-results.json](runtime-results.json)。以下操作均在恢复系统窗口之后、同一 App 进程 PID `2888` 内完成，没有中途重启 App：

- 设备内部请求 `127.0.0.1:8317/health` 返回 200。
- 本机 `/v1/models` 不带 Authorization、携带固定错误 Bearer 时均返回 401，含 `WWW-Authenticate: Bearer` 和 `invalid_api_key`。
- 切换本机 / 局域网展示并选中第二个地址后，监听 socket 仍是同一个 `socket:[28247]`；设备内部访问 `127.0.0.1`、`192.168.50.1`、`192.168.50.2` 的 `/health` 均返回 200。
- 点击 UI 总开关关闭后，`8317` 不再监听，ADB 转发的健康请求失败为 `RemoteDisconnected`，`PathPilot:LanRouter` 唤醒锁已释放。

没有配置账号或供应商、读取或复制完整共享密钥，也没有调用上游模型。

## 测试网络、环境与清理

初始设备只有回环地址及系统标记为蜂窝的 `eth0 = 10.0.2.15/24`。先验证本机默认页，再通过该 AVD 的 root adb 给原有 `dummy0` 临时添加 `192.168.50.1/24` 与 `192.168.50.2/24`。这些是实际 Android 内核地址，不是发布代码里的测试值；测试覆盖同接口多 IPv4 和本机监听，不代表实体多网卡或另一台局域网设备联调。

主机没有 KVM，使用软件模拟；安装后执行 `cmd package compile -m speed -f` 预编译，因此本轮是预热后的功能验证。启动阶段出现过 Quickstep 和 SystemUI 无响应，各关闭一次；系统窗口随后黑屏、`mCurrentFocus=null`，仅重启 capture App 一次恢复。四张最终截图及上述网络检查全部在这次恢复之后完成。首次 App 启动后的日志未见 capture ANR 或 `FATAL EXCEPTION`；功能测试时段未见 ANR，crash 缓冲为空。此记录不宣称冷启动性能或整个启动阶段无 ANR。

结束前已关闭共享、移除两个临时 IPv4（保留原有 eth0 地址）、删除本次 ADB 转发、仅清空 capture 测试包数据，并停止本次启动的模拟器。AVD 用户数据和截图保留。
