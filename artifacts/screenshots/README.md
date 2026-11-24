# PathPilot 实际运行截图

Android 15 / API 35 模拟器，720×1280，直接使用 `adb exec-out screencap -p` 导出原始 PNG，未合成或修图。

截图使用独立调试应用 `dev.pathpilot.app.capture`，与正常应用隔离。未配置账号或路由，未填写任何真实凭据，未开启共享；连接区域的局域网密钥保持遮罩。正常交付包仅包含 ARM64，仍保留防截屏。截图所用 x86_64 测试包仅为当前云端模拟器服务，不作为用户交付架构。

| 页面 | 原始图片 |
| --- | --- |
| 共享主页 | [01-sharing.png](01-sharing.png) |
| 供应商 | [02-providers.png](02-providers.png) |
| 路由 | [03-routes.png](03-routes.png) |
| 设置 | [04-settings.png](04-settings.png) |
| 添加供应商表单 | [05-add-provider.png](05-add-provider.png) |
| 连接地址和遮罩密钥 | [06-sharing-connection.png](06-sharing-connection.png) |

截图中的地址属于模拟器自身网络，不是可从用户设备访问的预览链接。
