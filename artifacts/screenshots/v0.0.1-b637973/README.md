# 0.0.1-b637973：导航调整过程记录

此批次记录调整前的五栏导航。后续按用户要求改为「共享、模型、MCP、设置」四栏；最终界面另行归档，本目录保留原始证据。

设备为 Android 15 / API 35、720×1280、x86_64 软件模拟器。普通批次为 360dp / font 1.0，大字体为 320dp / font 1.5。使用隔离 `.capture` 包和离线配置；模型与 MCP 上游均为本机测试服务。

| 批次 | 结果 |
| --- | --- |
| [normal-attempt1](normal-attempt1/results.json) | 3 项中 2 项通过；MCP 在完成界面与 HTTP 断言后，于测试 Activity 清理失败，整项仍计为失败。 |
| [statistics-final](statistics-final/results.json) | 统计导航 1 项通过。 |
| [large-final](large-final/results.json) | 大字体设置、导航、备份和 MCP 编辑器 1 项通过。 |
| [warm-start](warm-start/metadata.json) | 新 APK 实际启动及原始深色共享首页。 |

MCP 清理失败的原因已核对 AndroidX Test 1.7.0 源码：测试先以 MAIN/LAUNCHER 启动，再发送缺少这两个属性的 Intent；应用更新 Intent 后，ActivityScenario 不再匹配后续生命周期。修正使用真实通知的启动 Intent，并在失败时仍恢复测试配置。产品通知同时补充 SINGLE_TOP / CLEAR_TOP，使已存在的页面能接收 MCP 跳转。修正后的结果以新版批次为准，不改写本次失败。

## 系统栏核查

浅色时钟的原始像素主色为 RGB(100,99,102)，背景为 RGB(250,248,255)，见 [像素记录](system-bars/clock-pixels.json)。深色重建首帧曾出现浅色系统栏背景；保持同一 Activity 稳定后，[深色原图](system-bars/03-dark-fixed-steady.png)的背景和图标均正常，没有新增主题重试或焦点监听补丁。

每批包含源码与 APK 校验值、设备参数、runner 输出和原始 PNG。失败、复测和诊断分别保存。
