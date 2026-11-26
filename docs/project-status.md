# 项目状态

更新日期：2026-11-27（香港时间）。最终源码 [`ee2b4dd`](https://github.com/Ktclat/pathpilot/commit/ee2b4dd539aecad2a98f22b4c33dee15f300c6d6) 已推送 `main`，ARM64 APK 版本为 **`0.0.1-ee2b4dd`**，`versionCode` 12。详细构建与设备证据见 [验证记录](validation.md)。

## 本轮实现

| 工作 | 当前行为 |
| --- | --- |
| 导航 | 共享、模型、MCP、设置四个主页面；详情和统计隐藏主导航 |
| 模型管理 | 一级供应商，二级统一管理配置、登录、获取模型及共享模型；修复 API 来源自动获取目录被 Codex 登录判断阻止 |
| 统计 | 模型首页右上角进入；最近请求与具体模型仍可跳转，返回保留来源层级 |
| 界面一致性 | 复用共享开关、地址和密钥控件，中文与英文同步；修复浅色供应商编辑 Dialog 的白色系统栏文字 |
| 版本与构建 | `0.0.1-<实际提交前7位>`；APK 与发布标签使用同一实际版本 |
| 文档与 MCP | 补齐架构、贡献、验证归档及 GitHub PAT/客户端接入方法，核查官方、Codex、Magpie 和 CC Switch 实现 |

## 验证状态

| 检查 | 状态 |
| --- | --- |
| 本地 Gradle | 最终完整检查及截图包构建通过 |
| JVM | 核心 110 项、App 51 项，0 失败、0 错误 |
| Android Lint | 0 错误、42 条警告 |
| 最终 ARM64 APK | `ee2b4dd` 已构建；ZIP、包名、签名和 ARM64-only 检查通过 |
| 工作流与资源 | actionlint、506 对双语资源名称及文档链接检查通过 |
| GitHub Actions | 最终 [运行 45863708697](https://github.com/Ktclat/pathpilot/actions/runs/45863708697)成功 |
| 本轮模拟器 | 基线 12 项与最终包 4 次目录执行全部通过，共 13 项不同用例、16 次执行；最终版本及浅/深色编辑器原图已核对 |
| Release | 本轮未发布 |

前序 `b637973` 的五页导航截图和中途失败保留为历史记录，不作为四页版已通过设备检查的依据。`0.1.10` 的 9 项不同设备用例及大字体启动 ANR 已[归档](validation-0.1.10.md)。

## 待完成与能力边界

- 当前模拟器的深色三键导航仍呈低对比，应用窗口明暗标记与 Taskbar 明暗值已按主题切换；根因尚未确定，待跨设备复现。未据此增加主题补丁，详见 [原图与对照记录](../artifacts/screenshots/v0.0.1-ee2b4dd/README.md)。
- GitHub 远程 MCP 的 [PAT 配置方法](android-mcp.md)已有文档；真实服务协商和账号联调受当前环境 CONNECT 403 阻碍，尚未通过兼容验证。
- 官方 MCP 目录仍是已核查的[接入方案](mcp-catalog-research.md)，未加入目录 UI。MCP 目前实现 2025-11-25 Streamable HTTP Tools，支持无认证或静态 Bearer；OAuth、stdio 运行时、Resources、Prompts 和其他协议版本待适配。
- 模型协议范围见 [API 文档](api.md)。统计基于模型最近 1,000 条持久记录和 MCP 最近 30 条内存记录，不提供订阅余额或估算账单。
- 常规分发为 ARM64 debug 开发包；真实订阅账号、外部服务和实体手机跨设备联调按具体结果单独记录。
