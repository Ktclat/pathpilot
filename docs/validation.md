# 0.0.1-ee2b4dd 验证记录

更新日期：2026-11-27（香港时间）。最终 APK 源码提交为 [`ee2b4dd539aecad2a98f22b4c33dee15f300c6d6`](https://github.com/Ktclat/pathpilot/commit/ee2b4dd539aecad2a98f22b4c33dee15f300c6d6)，已推送 `main`。本轮将导航收敛为四页，统一供应商与模型管理，并完善手机 MCP 操作文档。[0.1.10 验证记录](validation-0.1.10.md)已归档。

## 改动

- 主导航为共享、模型、MCP、设置。模型首页按供应商组织，二级统一编辑配置、登录、获取模型和管理共享模型。
- 修复 API 供应商二级页点击「获取模型」后未自动请求目录：此前错误要求 Codex 登录状态；现在仅 Codex 检查登录，API 来源按自己的配置获取。另修复供应商编辑 Dialog 在浅色主题下使用白色系统栏文字，只调整该 Dialog 的图标明暗。
- 统计从模型首页右上角进入；保留最近请求、供应商和模型的统计入口。统计、供应商模型详情及 MCP 连接详情隐藏主导航，返回恢复来源层级。
- 共享开关、地址和密钥控件共用实现；中文与英文保持一致。MCP 通知复用已有 Activity 并打开 MCP 首页。
- 应用版本采用 `0.0.1-<实际检出提交前7位>`，`versionCode` 保持递增。本次为 12；Actions 从 APK 读取版本，发布标签对应 `v<APK版本>`。
- [手机 MCP 操作说明](android-mcp.md)提供 GitHub PAT 与 Codex 客户端配置，说明远程服务、手机网关和 Agent 的分工；[来源研究](mcp-catalog-research.md)区分远程目录与本地包安装。目录 UI 与手机通用脚本运行时尚未实现。

## 本地构建

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.0.1-ee2b4dd-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.0.1-ee2b4dd-arm64.apk
```

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Gradle | 最终 `ee2b4dd` 完整检查通过，1 分 45 秒；隔离截图包构建通过，32 秒 | [完整检查](../artifacts/validation/v0.0.1/gradle-editor-bars.log) · [截图包构建](../artifacts/validation/v0.0.1/capture-editor-bars.log) |
| JVM | 核心 110 项、App 51 项，0 失败、0 错误 | [逐模块汇总](../artifacts/validation/v0.0.1/jvm-results.json) |
| Android Lint | 0 错误、42 条警告 | [XML 报告](../artifacts/validation/v0.0.1/lint-results-debug.xml) |
| 最终 ARM64 APK | `ee2b4dd` 产物已构建；ZIP、包名、签名和 ARM64-only 检查通过 | [产物元数据](../artifacts/validation/v0.0.1/apk.json) |
| 工作流与资源 | actionlint 已通过；506 对中英文资源名称一致，无重复，文档链接检查通过 | 当前仓库检查结果 |
| GitHub Actions | 最终 `ee2b4dd` 构建成功 | [最终运行 45863708697](https://github.com/Ktclat/pathpilot/actions/runs/45863708697) · [运行记录](../artifacts/validation/v0.0.1/actions.json) |
| 模拟器 | 13 项不同用例、16 次执行全部通过，包含基线 12 项与最终包 4 次定向执行 | [基线](../artifacts/screenshots/v0.0.1-4e19d6e/README.md) · [最终包](../artifacts/screenshots/v0.0.1-ee2b4dd/README.md) |

交付产物为 `artifacts/PathPilot-0.0.1-ee2b4dd-arm64.apk`：版本 `0.0.1-ee2b4dd` / `versionCode` 12，包名 `dev.pathpilot.app`，仅包含 `arm64-v8a`，大小 21,088,845 字节。

SHA-256：`00aa149c93e76cc2e706709f5cba3e2d82d309e94db4be6db772258a90bd28c6`。

常规产物是 ARM64 debug 签名 APK；模拟器使用独立的 x86_64 `.capture` 包，二者分别记录。版本后缀对应实际构建提交，不随后续文档提交替换。

## 设备验证

设备检查按源码和影响范围分别记录：

| 源码 | 范围 | 当前结果 |
| --- | --- | --- |
| `4e19d6e` | 四页导航、设置、MCP、统计、3 项 Codex 目录、3 项供应商模型及 2 项大字体，共 12 项 | 12 项全部通过，相关原图已审阅 |
| `ee2b4dd` | API 供应商自动获取目录 1 项、Codex 目录 3 项复测 | [4 项全部通过](../artifacts/screenshots/v0.0.1-ee2b4dd/catalog-final/results.json)，目录与浅色编辑页原图已审阅 |

最终修复改变 API 目录获取的触发条件和供应商编辑 Dialog 的系统栏明暗，没有修改四页导航布局。保留 `4e19d6e` 的导航验证基线，对最终包执行受影响的目录流程与编辑页检查；中间版 `db21b5c` 不另作设备重复测试。两批合计覆盖 13 项不同用例、16 次执行，全部通过；3 项重复的 Codex 用例不重复计入不同用例数。

Codex 与供应商分组用例直接挂载生产组件，不代表完整 MainActivity 导航。完整主导航证据来自基线 `normal-attempt1` 的 17–20 号原图；新增 API 用例通过生产 MainActivity 完成实际流程。每批同时保留 runner、APK SHA 和原始截图。

最终包的 API [一次获取](../artifacts/screenshots/v0.0.1-ee2b4dd/catalog-final/24-api-model-selection.png)、[保存后模型页](../artifacts/screenshots/v0.0.1-ee2b4dd/catalog-final/25-api-model-saved.png)和[浅色编辑器](../artifacts/screenshots/v0.0.1-ee2b4dd/catalog-final/01a-provider-settings.png)已审阅，浅色 Dialog 时钟颜色修复已确认。同一最终包的[关于页](../artifacts/screenshots/v0.0.1-ee2b4dd/system-bars/05-about-final-version.png)显示 `0.0.1-ee2b4dd`（12）；[深色编辑器](../artifacts/screenshots/v0.0.1-ee2b4dd/system-bars/06-provider-editor-dark-steady.png)的白色时钟与深色背景匹配，浅色编辑器则使用灰色时钟。以上人工检查与原图审阅已完成。

此模拟器仍有深色三键导航低对比的呈现差异。浅/深对照使用同一个 Activity 窗口，各静置 15 秒；应用窗口的状态栏/导航栏明暗标记正确，Launcher3 Taskbar 的 `darkIntensity` 从 1 变为 0，但背景颜色 override 仍为 1，三键图标保持 RGB（69, 70, 79）。该差异仍未解决，具体平台原因与其他设备表现待复现。原图与窗口记录见[本批截图说明](../artifacts/screenshots/v0.0.1-ee2b4dd/README.md)。

验收后已恢复设备参数，清理两个 `.capture` 包的数据并停止本轮启动的模拟器。[设备结果汇总](../artifacts/validation/v0.0.1/device-results.json)与[清理记录](../artifacts/screenshots/v0.0.1-ee2b4dd/cleanup.json)已保存。

前序五页导航 `b637973` 记录单独保留：普通字体首批 2 项通过，MCP 用例在退出清理阶段失败；统计复测 1 项通过，大字体 1 项通过。相关通知返回栈修正已纳入当前代码，已由四页导航基线的 MCP 流程重新验证通过。旧图不作为四页导航的展示证据。

更早的 `0.1.10` 已确认 9 项不同设备用例通过；其大字体补跑在用例执行前遇到启动 ANR，失败与原图保留在 [归档](validation-0.1.10.md)。

## 外部服务与发布

官方 MCP 资料已按固定提交核查。当前环境对 Registry 及相关远程 MCP 域名的连接在代理 CONNECT 阶段返回 403，请求未到达服务；真实 GitHub PAT 握手与工具调用尚未验证。具体来源和协议依据见 [手机 MCP 文档](android-mcp.md)。

本轮尚未发布新的 Release。真实订阅账号、外部 MCP 服务和实体手机跨设备联调与本地回环测试分别记录。
