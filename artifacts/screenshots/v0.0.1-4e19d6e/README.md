# 0.0.1-4e19d6e：四页导航实际截图

截图来自 Android 15 / API 35 模拟器中运行的原生 Compose 页面，使用独立 `.capture` 应用与离线上游。生产源码为 [`4e19d6e`](https://github.com/Ktclat/pathpilot/commit/4e19d6e5365defe5df9cc080da1a5f5e65d9f9cb)，原始 PNG 未修改。

普通字体批次为 720×1280、density 320、font scale 1.0（360dp）；大字体为 density 360、font scale 1.5（320dp）。常规安装包仅包含 ARM64，本目录的 x86_64 包只用于模拟器。

## 页面核查

- 主导航只有共享、模型、MCP、设置，设置保留独立页面和滚动位置。
- 模型首页按供应商列出，右上角提供添加与统计入口；供应商子页管理配置和模型，二级页隐藏底部主导航。
- 统计从模型或请求入口打开，返回恢复来源页面。
- MCP 根页显示主导航，连接详情与编辑浮层保留各自返回方式。
- 中英文标签、浅色与深色设置沿用同一套组件和字体。

| 页面 | 原始截图 |
| --- | --- |
| 共享 | [共享首页](normal-attempt1/17-primary-0.png) |
| 模型 | [供应商列表](normal-attempt1/18-primary-1.png) · [供应商模型子页](normal-attempt1/21a-provider-models-secondary.png) |
| 统计 | [独立统计页](normal-attempt1/21-statistics-secondary.png) |
| MCP | [首页](normal-attempt1/19-primary-2.png) · [连接地址](normal-attempt1/10a-mcp-access.png) · [工具开关](normal-attempt1/10-mcp-tools.png) · [最近调用](normal-attempt1/12a-mcp-success-call.png) · [通知返回](normal-attempt1/22-mcp-notification-entry.png) |
| 设置 | [中文](normal-attempt1/20-primary-3.png) · [英文](normal-attempt1/05-settings-english.png) · [备份与版本](normal-attempt1/06-settings-backup-english.png) |
| 大字体 | [四项导航](large-final/14a-navigation-large-font.png) · [MCP 首页](large-final/16a-mcp-root-large-font.png) · [编辑表单](large-final/16-mcp-editor-large-font.png) · [统计指标](large-final/06-statistics-large-font.png) · [长模型名](large-final/08-models-large-font.png) |

MCP 最近调用中的「工具未共享」是测试主动关闭工具后的预期拒绝，同批也验证已允许工具调用成功。

## 设备结果

| 批次 | 结果 |
| --- | --- |
| [normal-attempt1](normal-attempt1/results.json) | 3 项通过：四页及二级导航、主题/语言、MCP 实际回环 HTTP 与通知入口。 |
| [statistics-final](statistics-final/results.json) | 1 项通过：从模型和共享请求打开统计，返回恢复来源页。 |
| [model-flows-final](model-flows-final/results.json) | 6 项通过：Codex 登录状态/目录/重试，供应商分组、模型筛选与开关。 |
| [large-final](large-final/results.json) | 2 项通过：320dp / 1.5 倍字体下的导航、设置、MCP 和统计操作。 |

模型流程批次直接在测试宿主中挂载生产组件，用于验证模型管理；其截图不包含 MainActivity 的完整导航。四页导航的原图以 `normal-attempt1/17`–`20` 为准。

本批模型编辑截图还记录了浅色全屏编辑器的白色状态栏图标问题，后续提交 `ee2b4dd` 已针对该 Dialog 窗口修正，最终截图另行记录。原图保留，不改写本批状态。

本批共 12 项通过。较早五页版的失败记录保留在[过程归档](../v0.0.1-b637973/README.md)，不算作本批通过结果。

## 源码与构建

- Capture APK SHA-256：`f627cd48eee30d924f2afa3239e0ce1ca9c67ee1dab10dbb9edc56a7b280da36`
- Test APK SHA-256：`c30a12fea484db1184c11504c922b1f17adbd5361aea01cfc4fd10b3f96d5777`
- 每批目录保留 `metadata.json`、`source-files.json`、`instrumentation.log`、`results.json` 和原始截图。
- [总验证记录](../../../docs/validation.md)区分构建、模拟器、真实账号和外部服务结果。
