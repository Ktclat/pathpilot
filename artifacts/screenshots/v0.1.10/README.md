# v0.1.10 设置、备份与 MCP：模拟器验证

2026-11-25，Android 15 / API 35、x86_64 软件模拟器。截图来自实际运行的隔离 `.capture` 包，常规交付 APK 仍为 ARM64。PNG 保留原始 720×1280 像素，没有裁切、缩放或重绘；测试只使用离线配置和设备回环 HTTP 上游。

## 展示截图

| 原图 | 内容 |
| --- | --- |
| [中文设置](normal-attempt1/01-settings-light.png) | 原生偏好、主题、莫奈取色、语言、连接分组。 |
| [主题选择](normal-attempt1/02-theme-picker.png) | 跟随系统、浅色、深色。 |
| [语言选择](normal-attempt1/04-language-picker.png) | 系统语言、简体中文、English。 |
| [英文设置](normal-attempt1/05-settings-english.png) | 实际切换应用语言并重建 Activity 后。 |
| [英文备份入口](normal-attempt1/06-settings-backup-english.png) | 设置下部的备份与应用选项。 |
| [备份密码](backup-attempt2/07-backup-export.png) | 真实设置导出弹窗，密码与确认、可选订阅登录状态。 |
| [恢复预览](backup-attempt2/08-backup-preview.png) | 解锁实际加密文件后显示配置数量，默认保留本机连接设置。 |
| [Android 文件器](backup-attempt2/13-backup-system-picker.png) | 生产 MainActivity 打开系统 CreateDocument，测试随后取消。 |
| [深色设置](steady-dark/03-system-night.png) | App 深色、莫奈取色开启；此图系统也处于夜间模式。 |
| [MCP 连接编辑](mcp-attempt3/09-mcp-editor.png) | 名称、URL、无认证/Bearer、Token；键盘已收起，保存按钮完整。 |
| [MCP 地址与密钥](mcp-attempt3/10a-mcp-access.png) | 本机/局域网入口、独立访问密钥和共享开关。 |
| [工具选择](mcp-attempt3/10-mcp-tools.png) | 实际获取两个本地测试工具，关闭其中一个。 |
| [MCP 共享](mcp-attempt3/11-mcp-sharing.png) | 前台服务运行、连接启用及允许工具数。 |
| [拒绝调用](mcp-attempt3/12-mcp-calls.png) | 客户端调用已禁用工具，界面显示「工具未共享」。 |
| [成功调用](mcp-attempt3/12a-mcp-success-call.png) | 同一入口成功调用允许的 `offline_status`，显示耗时与时间。 |

主题、语言、MCP、系统文件器和大字体用例启动生产 `MainActivity`。备份文件往返用例在测试 `ComponentActivity` 挂载生产 `SettingsScreen` / ViewModel，仅用 ActivityResultRegistry 返回测试私有文件 URI，文件加密、ContentResolver 读写、预览与 AppStore 恢复均走生产实现。

MCP 连接名称明确带有「离线测试」。地址和工具来自 Android 测试进程内的 MockWebServer，不是外部 MCP 服务或真实订阅账号。

## 分批记录

| 目录 | 源码版本 | 配置与结果 |
| --- | --- | --- |
| [normal-attempt1](normal-attempt1/results.json) | `747af3f` | 360dp、字体 1.0。9 项中 7 项通过、2 项等待失败；runner 637.633 秒。 |
| [mcp-attempt2](mcp-attempt2/results.json) | `853a3fe` | 1 项失败，116.896 秒；测试对 `ImeAction.Default` 调用 `performImeAction()` 被 Compose 拒绝。 |
| [mcp-attempt3](mcp-attempt3/results.json) | `22f1fca` | 1 项通过，170.688 秒；实际输入窗口隐藏键盘后完成全部 MCP 流程。 |
| [backup-attempt2](backup-attempt2/results.json) | `22f1fca` | 2 项通过，573.021 秒；系统文件选择器，以及导出、错误密码、解锁、预览和恢复。 |
| [large-final](large-final/results.json) | `22f1fca` 的旧 APK | 2026-11-26 恢复环境后补测，测试进程启动超时，runner 在执行用例前退出；0 项执行，0 张用例截图。 |

每批的 `results.json` 包含 APK SHA-256、源码 manifest、设备配置、逐例状态和图片 SHA；`instrumentation.log` 保存原始 runner 输出。`source-files.json` 记录当次源码文件校验值。测试 APK 与生产 APK 分开记录，不能用截图包代替常规 ARM64 交付。

首批通过的五项存储检查包括：Android Keystore 加密备份跨重开恢复、可选订阅登录状态、默认保留本机连接设置、AtomicFile 写入失败时保留已发布状态、损坏配置恢复，以及旧版模型端口 8318 迁移后分配 MCP 8319 并保持新密钥。主题/莫奈/语言选择与 Activity 重建后的持久化通过；MCP 首批真实 HTTP 流程也通过。

首批两处失败保留在原日志中：实际系统文件器入口等待 60 秒超时；备份 UI 已导出并解码文件，但等待错误密码文案 60 秒超时。复测仅将这些密码处理和文件器等待调整到 180 秒，产品 PBKDF2 迭代次数不变。第一次键盘 helper 只处理 Activity 窗口，无法关闭 Dialog/BottomSheet 的 IME；改用 `performImeAction()` 后又证实字段语义为 Default。最终 helper 按实际聚焦窗口的 token 隐藏 IME，并检查可见状态。没有在产品中加入测试专用逻辑。

## 深色与系统栏对照

[首次深色截图](normal-attempt1/03-settings-dark.png) 在 Activity 重建后出现浅色系统栏背景。手动保持深色并冷启动后，[稳态图](steady-dark/02-dark-relaunched.png) 上下背景均为深色，应用窗口没有 `LIGHT_STATUS_BARS` / `LIGHT_NAVIGATION_BARS` 标记，状态栏文字正常，但此 AVD 的三键导航图标仍为深灰。仅把平台 `cmd uimode night` 改为 `yes` 后，[对照图](steady-dark/03-system-night.png) 的导航图标变为浅色。原系统模式 `no` 已恢复，未据此修改正确的应用主题逻辑。详细时序、窗口转储和图像校验值见 [steady-dark/metadata.json](steady-dark/metadata.json)。

## 启动与清理

模拟器第一次启动、安装本轮 App 前出现 Quickstep 和 System UI ANR。实际弹窗原图保留在 [boot](boot/)；逐个查看并关闭后恢复桌面，再安装、预编译与启动 App。首批不是在这些弹窗遮挡下运行。

备份复测已完成；截至环境恢复后读回结果，共 9 项不同用例通过。旧 APK 大字体补测在启动阶段失败，详见 [诊断记录](large-final/diagnosis.json)，未记为通过。本轮后续使用新提交的 APK 检查大字体；设备清理在新版本验证记录中归档。
