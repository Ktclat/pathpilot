# 项目状态

更新日期：2026-11-26。此页区分已验证基线与当前改动；详细结果和原始证据见 [验证记录](validation.md)。

## 已验证基线

最后确认已推送的提交为 [`22f1fca`](https://github.com/Ktclat/pathpilot/commit/22f1fca9d0c3eb6060ec90ccd590a3b922b12d50)，应用版本 `0.1.10`，`versionCode` 为 11。

| 检查 | 已确认结果 |
| --- | --- |
| JVM 测试 | 核心 110 项、App 51 项通过 |
| Android Lint | 0 错误、42 条警告 |
| 常规 APK | ARM64-only，包名、ZIP 与签名检查通过 |
| GitHub Actions | [`22f1fca` 构建](https://github.com/Ktclat/pathpilot/actions/runs/45599744622)通过 |
| 模拟器 | API 35、x86_64 隔离截图包；9 项不同用例已通过 |

设备结果包括 5 项备份存储测试、主题/Monet/语言、MCP 工具共享、Android 文件选择器及备份 UI 加密文件往返。上次环境断连前启动的备份复测已完成，恢复后从 [`backup-attempt2/results.json`](../artifacts/screenshots/v0.1.10/backup-attempt2/results.json) 确认两项通过。大字体用例尚待完成，不能记为全部设备检查通过。

这些测试使用离线配置和回环 HTTP 上游。真实订阅账号、外部 MCP 服务与实体手机跨设备调用的验证范围以具体记录为准。

## 当前整理与改进

以下工作正在进行，完成后以实际提交、构建和截图更新：

| 工作 | 目标 |
| --- | --- |
| 仓库文档 | 精简 README，补充架构、贡献与状态文档，关联原始验证记录 |
| 导航 | MCP 成为主导航目的地；设置从顶栏进入；详情页不重复显示主导航 |
| 界面一致性 | 共享控件与状态保持一致，删除重复说明，保持双语资源同步 |
| 版本 | 从 `0.0.1-<实际提交前7位>` 起步，`versionCode` 继续递增到 12 |
| MCP 来源 | 核查官方远程服务和 Registry，区分一键添加 HTTP 连接与安装本地 stdio 进程 |
| 设备验证 | 安装本轮 APK，审查受影响页面、返回行为和大字体，保存实际截图 |

## 当前能力边界

- 模型协议：Models、Chat Completions、Responses；各供应商支持范围见 [API 文档](api.md)。
- MCP：2025-11-25 Streamable HTTP Tools；无认证或静态 Bearer。OAuth、stdio 运行时、Resources、Prompts 和其他协议版本尚未实现。
- 统计：模型请求最近 1,000 条持久记录；MCP 最近 30 条内存记录。没有订阅余额或估算账单。
- 分发：ARM64 debug 开发包；本轮尚未发布新的 Release。
