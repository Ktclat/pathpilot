# 0.0.1-ac963f2：主题与远程 MCP 预设

源码 [`ac963f2`](https://github.com/Ktclat/pathpilot/commit/ac963f2e59a8af13a66ae950233d6fd102dd423a)，版本 `0.0.1-ac963f2` / `versionCode` 13。Android 15 / API 35 模拟器实际安装运行，720×1280，独立 `.capture` 包；正常交付 APK 仅包含 ARM64。

## 运行范围

- 普通字体：density 320、font scale 1.0，主题与语言、两项 MCP 预设、自定义连接与共享，共 4 项。
- 大字体：density 360、font scale 1.5，主题和 MCP 操作，共 1 项。
- 手动检查：生产 MainActivity 中的预设目录及关于页版本。

主题、自定义 MCP 和大字体流程运行生产 MainActivity。两个预设流程在测试 Activity 中挂载生产 `McpPage` 和 AppStore，通过真实 HTTP 客户端请求设备回环服务器；保存的服务 URL 仍为官方地址。图中的工具和凭据为测试数据，不代表远程服务或真实账号联调。

## 设备结果与原图

[normal-final/results.json](normal-final/results.json)记录 4 项执行：3 项 MCP 流程通过，主题用例出现 Espresso 空闲同步超时。同一 APK、测试规则与时限单独运行该主题用例后通过，见 [theme-isolated/results.json](theme-isolated/results.json)。首次批次的 MCP 跨线程异常在切换调度器后未再出现；主题开场超时的具体原因尚未确定，不将独立复测通过写成根因已修复。

| 场景 | 原图 |
| --- | --- |
| 设置主题入口 | [主题摘要](normal-final/26-settings-theme-entry.png) |
| MCP 四项远程预设 | [添加连接](normal-final/37-mcp-preset-catalog.png) |
| 免凭据来源与失败重试 | [503 反馈](normal-final/38-mcp-preset-fetch-error.png) · [工具目录](normal-final/39-mcp-preset-tools.png) |
| GitHub | [令牌表单](normal-final/40-mcp-github-token.png) · [工具目录](normal-final/41-mcp-github-tools.png) |
| Context7 | [API Key 表单](normal-final/42-mcp-context7-key.png) |
| MainActivity 的 MCP 页 | [四项主导航](normal-final/09a-mcp-root.png) · [自定义连接](normal-final/09-mcp-editor.png) |
| 共享与通知返回 | [共享运行](normal-final/11-mcp-sharing.png) · [通知返回](normal-final/22-mcp-notification-entry.png) |

上述截图已查看：字段、按钮和工具状态清楚，令牌保持遮罩；详情页不显示主导航。预设的 503 和未共享工具调用来自测试夹具，属于明确覆盖的失败流程。

| 主题场景 | 原图 |
| --- | --- |
| 中文动态取色 | [主题面板](theme-isolated/27-theme-dynamic.png) |
| 海蓝与深色、重建恢复 | [选择后](theme-isolated/28-theme-ocean-dark.png) · [Activity 重建后](theme-isolated/29-theme-ocean-restored.png) |
| 英文动态取色 | [Dynamic colors](theme-isolated/30-theme-dynamic-english.png) |
| 琥珀配色 | [Amber](theme-isolated/31-theme-amber-english.png) · [设置摘要](theme-isolated/32-settings-english-theme.png) |
| 英文设置与版本 | [备份和关于入口](theme-isolated/06-settings-backup-english.png) |

主题原图已查看，模式、色板选择、动态开关和设置摘要一致，中文与英文可读。深色图片中的系统三键仍呈低对比，与[前一轮的观测](../v0.0.1-ee2b4dd/README.md)一致，根因及其他设备表现待复现。

[large-final/results.json](large-final/results.json)的大字体自动流程在打开主题后发生 Compose 空闲超时，只产生[设置页](large-final/14-settings-large-font.png)与[导航检查](large-final/14a-navigation-large-font.png)两张原图。本轮最终包共 6 次自动执行：4 次通过、2 次失败，覆盖 4 个不同用例通过；大字体不计自动通过。

## 大字体人工检查

相同 APK 在测试框架外启动生产 MainActivity，保持 density 360 / font scale 1.5，通过实际点击和滚动完成下列检查；原图已逐张审阅。它确认当前界面可用，不覆盖上面的自动化失败记录。

| 操作 | 原图与结果 |
| --- | --- |
| 打开主题 | [模式换行、动态开关、完成按钮](manual-large/01-theme-controls.png)清晰可见 |
| 滚动色板 | [五种配色](manual-large/02-theme-palettes-visible.png)均可访问 |
| 选择固定色 | [琥珀](manual-large/03-amber-selected.png)、[海蓝](manual-large/04-ocean-selected.png)显示选中状态并实际变色 |
| 开启动态并完成 | [动态开关](manual-large/05-dynamic-enabled.png)开启，固定色取消选中；[返回设置](manual-large/06-settings-after-done.png)摘要一致 |
| 滚动 MCP 预设 | [上半部](manual-large/08-mcp-catalog-top.png)、[下半部](manual-large/09-mcp-catalog-bottom.png)包含完整四项预设与自定义入口 |
| 打开 GitHub | [令牌表单](manual-large/10-github-token-controls.png)的地址、输入框、获取令牌和添加按钮可达 |

手动过程未填写或提交凭据，未调用远程 MCP 工具。[采集元数据](manual-large/metadata.json)记录实际操作、设备参数与原图 SHA-256。

## 恢复普通字号后的启动记录

恢复 density 320 / font scale 1.0 后，一次冷启动超时，随后 MCP 触摸事件等待 5,001ms 触发[实际 ANR 弹窗](manual-main-activity/00-startup-anr.png)。[主线程](manual-main-activity/startup-anr-main-thread.txt)为 Runnable，采样在 Android Bidi/文字宽度测量及 Compose 布局计算；[原始记录](manual-main-activity/startup-anr-dropbox.txt)同时显示较高 CPU 压力。现有证据未确定根因。

选择 Wait 后仍看到原弹窗，[时间核对](manual-main-activity/recovery-lastanr.txt)保留同一事件，不能计作新的 ANR。随后仅关闭该测试进程并冷启一次，[启动记录](manual-main-activity/launch-recovery.txt)为成功，13.143 秒完成；[共享页原图](manual-main-activity/02-recovery-cold-static.png)无遮挡。恢复成功不替代失败记录，也不表示已修复其原因。

恢复后实际打开[普通字体 MCP 预设目录](manual-main-activity/03-mcp-preset-catalog.png)与[关于页](manual-main-activity/04-about-version.png)，均已审阅。关于页显示 `0.0.1-ac963f2（13）`，与交付 APK 一致。[最终设备值](manual-main-activity/final-device-values.json)确认 density 320、font scale 1.0、系统浅色；最后 ANR 仍是原 04:53:40 事件。

[汇总](validation-summary.json)保留各批结果和验证范围。审图后已恢复设备参数、清理两个隔离截图包并停止本轮模拟器，见[清理记录](cleanup.json)。

## 构建来源与首次失败

- Capture APK SHA-256：`5d3f8c4e558157180e76b289c377765d84da94c648e123b7c43a37f5facd017b`
- Test APK SHA-256：`1dd472398d3d0ea0721346ffc1a92800cac50a4ee776d7927a766bd50af144c1`
- 正常 ARM64 APK、构建和 CI 记录见[验证文档](../../../docs/validation.md)。

[首次 aff1629 批次](../v0.0.1-aff1629/README.md)保留 3 项失败、1 项未运行和手动对照。后续提交只调整 Compose 测试调度器，未据此改动产品或放宽断言；[诊断记录](../../validation/v0.0.1-ac963f2/test-scheduling.json)列出日志和官方源码依据。
