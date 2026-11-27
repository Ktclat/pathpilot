# 项目状态

当前交付 APK 为 **`0.0.2+d58b2f4`**，Android `versionCode` 为 **14**，源码 [`d58b2f4`](https://github.com/Ktclat/pathpilot/commit/d58b2f448152c8b0c499709fad974577446cc519) 已推送 `main`。本轮完善 Codex 登录并集成 MCP 调用历史；本地完整检查、ARM64 CI 与 KVM 模拟器 7 项用例均通过。构建产物、测试源码差异和先前失败记录见 [验证记录](validation.md)。

## 本轮实现

| 功能 | 当前行为 |
| --- | --- |
| Codex 登录 | 增加浏览器 PKCE 登录，使用手机本机回调；保留设备代码登录 |
| 登录过程 | 临时轮询失败可继续等待，取消会释放请求和监听；错误本地化，保存失败单独重试 |
| MCP 历史 | SQLite 保存最近 1,000 条调用记录，可按日期、连接和工具筛选，查看结果与耗时，重试存储操作或清空记录 |
| 模拟器 CI | 独立工作流支持手动执行 Codex 登录、MCP 页面与历史存储用例，保留测试记录和截图 |
| 版本 | `version.properties` 统一维护基础版本和 Android `versionCode`；新分发版本递增第三段，实际提交以 `+` 追加 |

保留共享、模型、MCP、设置四个主页面；模型与 MCP 服务分别启停。主题提供系统/浅色/深色、动态取色与五种固定配色，设置支持语言、上游代理和加密备份。

## 验证状态

| 检查 | 当前状态 |
| --- | --- |
| JVM | 核心 125 项、App 75 项，共 200 项；0 失败、0 错误、0 跳过 |
| Lint 与常规 ARM64 APK | Lint 0 错误、42 条警告；APK 签名、ABI、包名与版本检查通过 |
| GitHub Actions | [运行 45986099940](https://github.com/Ktclat/pathpilot/actions/runs/45986099940)成功，APK 与报告已上传 |
| KVM 模拟器 | [运行 45990106903](https://github.com/Ktclat/pathpilot/actions/runs/45990106903)成功：登录 3 项、MCP 页面 1 项、SQLite 3 项，全部通过 |
| 本地模拟器 | 2 项通过、1 项失败、4 项未完成；原始结果与诊断单独保留 |
| 截图审查 | 已核对 MCP 历史页与静态登录界面；云浏览器与输入法过渡帧的范围见验证记录 |
| 真实 Codex 账号与远程 MCP | 本轮尚无成功联调记录 |
| Release | 本轮尚未发布 |
| 分支整理 | 三条相关分支的提交历史与功能已审阅并保留在当前源码中 |

KVM 使用测试源码 `d3977fe`，生产代码、资源、构建与版本配置和交付 APK 的 `d58b2f4` 相同；差异为测试、CI 与证据。设备通过结果来自隔离截图包及本地上游夹具，不代表真实账号授权成功。

上一轮 `0.0.1-ac963f2` 的构建、设备通过结果、自动失败和启动 ANR 观测已[原样归档](validation-0.0.1-ac963f2.md)。旧结果不计入本轮，未定位的问题也未标为已修复。

## 接入状态

- GitHub MCP 使用 PAT。OAuth 登录还需要 PathPilot 对应 OAuth 应用的 client ID；当前没有可直接交付的 GitHub OAuth 登录流程。
- Microsoft Learn、OpenAI Docs 可直接添加，Context7 填 API Key；预设保存后自动获取工具。官方字段已核查，真实远程握手仍待验证，详见 [手机 MCP](android-mcp.md)与[预设资料](mcp-presets.md)。
- 当前提供精选远程预设与自定义连接；在线 Registry 搜索和本地 stdio 运行时尚未实现。
