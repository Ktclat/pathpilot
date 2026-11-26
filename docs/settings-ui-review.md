# 0.1.10 设置与 MCP 页面审查

日期：2026-11-25。以下结论来自 Android 15 / API 35 模拟器实际运行截图；截图为原图，未修饰像素。

## 设置与语言

设置按偏好、连接、备份、应用分组。行内显示名称与当前值，主题、语言、MCP 连接数、版本等有对应的操作入口。浅色中文、英文和深色页面的字体层级一致；英文底部导航完整，页面可滚动到备份及应用选项。未发现这些已查看页面上的文字重叠。

- [浅色设置](../artifacts/screenshots/v0.1.10/normal-attempt1/01-settings-light.png)
- [主题选择](../artifacts/screenshots/v0.1.10/normal-attempt1/02-theme-picker.png)
- [英文设置](../artifacts/screenshots/v0.1.10/normal-attempt1/05-settings-english.png)
- [英文备份与应用选项](../artifacts/screenshots/v0.1.10/normal-attempt1/06-settings-backup-english.png)

主题、Monet 开关和语言选择经过实际点击，并在 Activity 重建后核对持久化状态。自定义供应商与上游工具的名称不强行翻译。

## 深色系统栏的复查

首次重建截图出现上下系统栏白底，未据此修改主题代码。后续保留了独立窗口和原图证据：

1. [重启后稳定深色图](../artifacts/screenshots/v0.1.10/steady-dark/02-dark-relaunched.png)显示上下背景已与页面匹配，状态栏正常；底部三键仍为深色。
2. [对应窗口记录](../artifacts/screenshots/v0.1.10/steady-dark/window-after-relaunch.txt)确认 MainActivity 全屏且没有 `LIGHT_STATUS_BARS` / `LIGHT_NAVIGATION_BARS` 位。
3. 保持 App 深色，仅将模拟器系统夜间模式改为深色，[三个系统按钮变为浅色](../artifacts/screenshots/v0.1.10/steady-dark/03-system-night.png)。随后恢复原系统模式。

以上对照支持这台 AVD 的 Taskbar 图标随系统夜间配色变化。记录了该模拟器差异，未新增焦点重试、监听或重复配色代码，也不将它归纳为所有实体 Android 手机的表现。

## 备份

导出使用原生弹窗，密码隐藏，订阅登录状态默认不勾选；内容说明只列出实际备份范围。恢复先选择文件、输入密码，再展示内容并确认替换。默认保留本机连接设置。

首批真实存储的 5 项用例通过。两个界面用例在密码运算或文件选择器的 60 秒等待处超时，保留失败记录；后续测试仅延长对应等待，并关闭实际输入窗口的键盘，不调整加密参数。测试曾使用未定义的 IME 动作而失败，该记录也单独保留；这不计为应用崩溃。最终复测结果与截图见 [验证记录](validation.md)。

## MCP

MCP 入口位于设置的连接组，连接列表和连接内工具分层展示。独立共享开关、工具开关和访问密钥沿用全局主题与字体。

首批实际 HTTP 联调验证了获取工具、单个工具开关、调用成功与拒绝、独立服务启停。审图发现调用记录直接显示 `tool_disabled`，已改为「工具未共享」等中英文标签；HTTP 与 JSON-RPC 保留诊断数字。原始失败及最终截图均保留，详见 [截图与设备记录](../artifacts/screenshots/v0.1.10/README.md)。

[地址与密钥](../artifacts/screenshots/v0.1.10/mcp-attempt3/10a-mcp-access.png)、[成功调用](../artifacts/screenshots/v0.1.10/mcp-attempt3/12a-mcp-success-call.png)、[恢复预览](../artifacts/screenshots/v0.1.10/backup-attempt2/08-backup-preview.png)已查看。备份 UI 复测两项均通过，大字体检查另行补齐。

本页记录 0.1.10 的界面。后续审查确认 MCP 嵌在设置内时外层底部导航仍显示；本轮将 MCP 改为独立主导航，并在连接详情和设置页隐藏主导航。修改后的结果单独记录，不覆盖旧版截图。
