# Codex and MCP emulator results

Source: `d3977fefd677801cc64d99ac185b812b6d6b819e`

Local fixtures only; no real account authorization.

| Suite | Expected | Passed | Failed | Skipped | Not finished |
| --- | ---: | ---: | ---: | ---: | ---: |
| codex-login | 3 | 3 | 0 | 0 | 0 |
| mcp-ui | 1 | 1 | 0 | 0 | 0 |
| mcp-sqlite | 3 | 3 | 0 | 0 | 0 |

Overall: passed.

Raw instrumentation, crash/events logs, APK hashes and original screenshots accompany this report.

## UI 审查范围

本批次保留 9 张内部截图与 8 张外部截图。[MCP 历史页](screenshots/internal/13-mcp-history.png)已核对筛选、统计与次级页隐藏主导航。[50](screenshots/internal/50-codex-device-after-browser.png)仍是 Browser2 网页，不能作为返回后的 App UI 截图；浏览器往返通过由 runner 的生命周期与设备码断言支持。[43](screenshots/internal/43-codex-browser-login.png)含淡入过渡，静态登录展示使用[本地批次 43](../v0.0.2-d58b2f4/auth-ui-final/43-codex-browser-login.png)；[MCP 编辑器 09](screenshots/external/settings-e2e/09-mcp-editor.png)底部有输入法过渡边缘。所有原图保持不变，过渡帧不作为静态布局依据。
