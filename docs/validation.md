# 0.1.10 验证记录

日期：2026-11-25（UTC）。本轮完善主题、Monet、中英语言、加密备份，并增加独立 MCP 页面与共享服务。[0.1.9 验证记录](validation-0.1.9.md)已归档。

## 实现与范围

- 设置按偏好、连接、备份、应用分组；主题有跟随系统／浅色／深色，Android 12+ 可用 Monet；语言有跟随系统／简体中文／English。
- `.ppbackup` 使用口令加密，包含供应商、模型、应用偏好和 MCP；订阅登录状态可选，不包含请求历史。恢复先预览，默认保留目标手机端口、代理和访问密钥；成功写入后更新界面。
- MCP 页面管理远程 HTTP(S) 连接、Bearer 凭据、工具清单及共享开关。独立前台服务提供 `/mcp/<connection-id>`；模型与 MCP 各自启停和鉴权。
- MCP 固定实现 2025-11-25 Streamable HTTP Tools：初始化、JSON/SSE、分页、会话、取消和调用结果；不将静态 Bearer 配置描述为 OAuth。后续范围见 [MCP 文档](mcp-roadmap.md)。
- 参考依据见 [CC Switch / Magpie / MCP 一手源码核查](settings-mcp-reference.md)。

## 自动验证

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.10-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.10-arm64.apk
```

本地完整构建已通过：核心 110 项、App 51 项 JVM 测试全部通过；Lint 0 错误、42 条警告。APK ZIP、包名、ARM64-only 与签名验证通过，actionlint 通过。首轮 Lint 发现资源读取未感知配置、整数占位符与 StateFlow 读取问题，已修正后重新执行，不计首轮为通过。

交付包 `artifacts/PathPilot-0.1.10-arm64.apk`，版本 `0.1.10` / versionCode 11，包名 `dev.pathpilot.app`，仅含 `arm64-v8a`。SHA-256：`ec799e15a21fc8674ceaa7c13bdd042c8bdbc7e67334ae725f1779ba9330684d`。这是常规 debug APK；截图包独立，不用于交付。

[Gradle 日志](../artifacts/validation/v0.1.10/gradle-reviewed.log) · [JVM 结果](../artifacts/validation/v0.1.10/jvm-results.json) · [Lint XML](../artifacts/validation/v0.1.10/lint-results-debug.xml)。最终生产源码 `853a3fe` 的 [Actions](https://github.com/Ktclat/pathpilot/actions/runs/45598564026) 和输入窗口测试修正 `22f1fca` 的 [Actions](https://github.com/Ktclat/pathpilot/actions/runs/45599744622) 均通过；此前 `5832341`、`747af3f` 的源码构建也通过。[CI 状态与提交](../artifacts/validation/v0.1.10/actions.json)已记录。模拟器已确认 9 项不同用例通过：5 项存储、主题与语言、MCP HTTP 联调、系统文件器、备份文件往返恢复；大字体验证尚待补齐。详见[原始截图与分批记录](../artifacts/screenshots/v0.1.10/README.md)。

核心测试覆盖真实 loopback HTTP 的 MCP 初始化、分页、JSON/SSE、独立鉴权、工具开关、会话隔离和停止取消；备份 JVM 测试覆盖加解密、损坏文件、版本和配置校验。测试使用离线夹具，不使用真实订阅或外部 MCP 账号。
