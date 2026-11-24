# v0.1.4 实际 Android 截图与验证

2026-11-24，在 Android 15 / API 35 x86_64 模拟器上运行独立测试包 `dev.pathpilot.app.capture`。画面为 720×1280、密度 320 的 `adb exec-out screencap -p` 原始 PNG，均已实际查看；没有编辑图像或注入 App 假数据。

全部最终截图来自 `PathPilot-0.1.4-ui-capture.apk`，SHA-256：

```text
ee8789903b6b5afda8c7b77e6e032b81974cb727e630c2903b06afde5f07162b
```

| 文件 | 实际画面 |
| --- | --- |
| [01-sharing-off.png](01-sharing-off.png) | 总开关关闭；地址、地址选择入口和 `sk-` 密钥掩码均在首屏。 |
| [02-address-selector.png](02-address-selector.png) | 两个实际内核 IPv4 地址，显示接口名称与当前选择。 |
| [03-sharing-on.png](03-sharing-on.png) | 总开关开启，选中第二个地址；没有供应商或路由时显示“已开启 · 暂无可用模型”。 |

## 网络测试设置

系统原有 `eth0 = 10.0.2.15/24`，Android ConnectivityManager 将其报告为蜂窝网络。为验证多个可选地址，通过该 AVD 的 root adb 给原有 `dummy0` 接口临时添加了 `192.168.50.1/24` 与 `192.168.50.2/24`。App 读取的是 Android 内核地址，发布代码没有测试地址或模拟数据。

这覆盖同一测试接口的多个 IPv4、接口名称展示和服务监听；不代表实体多网卡、VPN或另一台局域网设备的联调。

## 实际结果

最终包在 Activity 保持前台、同一进程 PID `3082` 内完成两轮“开启 → 关闭”，中途没有杀进程、清数据或重装：

| 检查 | 两轮结果 |
| --- | --- |
| 开启后 ADB 转发 `127.0.0.1:18317/health` | HTTP 200，`{"status":"ok"}`。 |
| 设备内分别访问 `.1:8317/health` 与 `.2:8317/health` | 两个地址均 HTTP 200，使用设备内 `toybox nc`，没有调用上游。 |
| 开启后的监听与唤醒锁 | `8317` 监听存在，`PathPilot:LanRouter` 唤醒锁存在。 |
| UI 关闭后的监听与 HTTP | `8317` 监听消失，转发请求失败为 `RemoteDisconnected`。 |
| UI 关闭后的唤醒锁 | `PathPilot:LanRouter` 唤醒锁消失。 |
| 再次开启 | 同一 PID 成功重新监听并返回健康响应。 |

最终包还验证了：共享关闭时移除当前所选 `.2`，页面经轮询自动回退 `.1`，地址选择入口随可用地址减少而消失。运行中移除 `.2` 后回退 `.1`，已在同轮初始测试包上验证。

初始测试包 SHA-256 `3f4ebfd07895a343215bc0683f5438169e92ab5f4de3cde72c2c980ff1ddccd9` 曾出现“UI 已关闭但监听仍在”的真实问题：捕获到其进程仍监听 `8317` 且健康请求仍返回 200。此问题推动了取消清理逻辑修复；以上最终包两轮检查是修复后的验证，升级结束旧进程本身不计作关停通过。中间仅调整布局的构建没有安装。

## 环境与清理

主机没有 `/dev/kvm`，使用软件模拟；安装后执行 Android 官方 `cmd package compile -m speed -f` 预编译。这是预热后的功能验证，不代表冷启动性能。模拟器启动阶段曾发生 SystemUI 无响应并关闭一次系统弹窗；最终包运行窗口内日志未见 capture 包 ANR、SystemUI ANR 或 `FATAL EXCEPTION`，crash 缓冲为空。

没有配置真实账号或上游密钥，也没有读取、复制或显示完整共享密钥。结束前已通过 UI 关闭共享，移除两个临时 IPv4（保留原有 eth0 地址）、删除本次 ADB 转发、仅清空 capture 测试包数据，并停止本次启动的模拟器；AVD 用户数据与截图保留。
