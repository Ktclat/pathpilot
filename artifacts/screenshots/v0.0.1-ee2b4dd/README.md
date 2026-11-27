# 0.0.1-ee2b4dd：模型获取与编辑器复查

最终产品源码为 [`ee2b4dd`](https://github.com/Ktclat/pathpilot/commit/ee2b4dd539aecad2a98f22b4c33dee15f300c6d6)。在[四页导航基线](../v0.0.1-4e19d6e/README.md)上修正 API 供应商自动获取模型及全屏编辑器系统栏图标颜色，使用新包定向复测。

## 模型目录流程

[catalog-final](catalog-final/results.json) 的 4 项全部通过：API 供应商一次点击获取并保存模型；Codex 已登录获取、401 后重试、未登录请求登录。API 流程使用真实 MainActivity 和回环 HTTP，断言请求路径、Bearer、请求次数与保存结果；Codex 流程直接挂载生产页面组件，替换凭据和上游地址。

| 场景 | 原始截图 |
| --- | --- |
| API 供应商模型子页 | [获取入口](catalog-final/23-api-provider-models.png) |
| 一次点击获取并选择模型 | [模型选择](catalog-final/24-api-model-selection.png) |
| 保存后返回模型子页 | [模型已保存](catalog-final/25-api-model-saved.png) |
| Codex 编辑器浅色状态栏 | [编辑供应商](catalog-final/01a-provider-settings.png) |
| Codex 目录与登录错误 | [模型选择](catalog-final/02-model-selection.png) · [401 反馈](catalog-final/03-auth-error.png) |

截图来自安装运行的 Android 15 / API 35 模拟器，720×1280、density 320、font scale 1.0，未修改原始 PNG。使用离线凭据与测试服务，不代表真实订阅账号联调。

基线 12 项与本批 4 次执行共覆盖 13 个不同用例；重复执行的 3 项 Codex 用例不重复计数。

## 版本和系统栏人工复查

- [关于页](system-bars/05-about-final-version.png)显示 `0.0.1-ee2b4dd（12）`，与最终 APK 元数据一致。
- [深色 DeepSeek 编辑器](system-bars/06-provider-editor-dark-steady.png)的白色时钟清晰；上面的浅色编辑器使用灰色时钟。编辑器没有主导航，未填写真实密钥或保存此供应商。
- 同一个 Activity 分别保存[冷启动深色](system-bars/01-cold-dark-steady.png)、[切换浅色](system-bars/03-light-steady.png)、[再切深色](system-bars/04-toggled-dark-steady.png)原图，各等待 15 秒稳定后采集。

**该模拟器的深色系统三键仍呈低对比度。** Home 图标为 RGB(69,70,79)。[浅色窗口](system-bars/03-light-window.txt)设置 `LIGHT_STATUS_BARS` / `LIGHT_NAVIGATION_BARS`，[深色窗口](system-bars/04-toggled-dark-window.txt)清除此标记；Launcher3 Taskbar 的 dark intensity 也从 1.0 变为 0.0，但背景颜色覆盖值保持 1.0，见[浅色 Taskbar](system-bars/03-light-taskbar.txt)与[深色 Taskbar](system-bars/04-toggled-dark-taskbar.txt)。

现有证据表明应用报告的明暗参数正确，系统三键呈现差异仍存在。此项作为未解决的设备呈现问题保留，具体平台原因及其他设备表现待进一步复现。

[人工核查元数据](system-bars/metadata.json)保留采集顺序、设备参数、像素值和文件 SHA-256。验收后已恢复字号、density 与系统明暗模式，清理隔离测试包并停止本轮启动的模拟器，见[清理记录](cleanup.json)。

## 构建来源

- Capture APK SHA-256：`4cf8e85ffa679b6178028c7635c2e8faeac32d780ec0e8e6b315d88d1a0a5e1a`
- Test APK SHA-256：`db3003a9d68743c8c3fb7602fa697fd48bdba7050177181ed71c305485a8ed47`
- 本版本正常 ARM64 产物、签名与完整构建记录见[验证归档](../../../docs/validation-0.0.1-ee2b4dd.md)。

测试截图包使用独立 `.capture` 包名；常规分发 APK 仅包含 ARM64。
