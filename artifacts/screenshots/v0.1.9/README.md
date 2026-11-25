# v0.1.9 统计页面：模拟器截图与验证

2026-11-25，Android 15 / API 35 软件模拟器。八个不同设备用例均取得通过结果：五个统计流程、主导航、大字体布局、趋势状态恢复。验证分三个代码版本、五批运行完成；首批大字体布局断言失败与修复后的复验分别记录。

## 展示截图

以下 11 张原始 PNG 均为 720×1280，已逐张实际查看，没有系统弹窗遮挡，没有缩放或裁切像素。`navigation-final` 和 `large-attempt2` 使用生产 `MainActivity`；`normal-attempt1/01–04` 在测试 `ComponentActivity` 中挂载生产 `StatisticsPage`。大字体由真实 Android 系统配置设置，不是图片放大。

| 原图 | 内容 |
| --- | --- |
| [共享首页](navigation-final/00-sharing-home.png) | 本机/局域网地址、访问密钥、五项底部导航。 |
| [完整统计主页面](navigation-final/09-statistics-main.png) | 四项指标、返回总 Token 的请求占比、独立统计导航。 |
| [统计空态](navigation-final/05-statistics-empty.png) | 请求为 0，未知指标为「—」。 |
| [统计组件概览](normal-attempt1/01-statistics-overview.png) | 四条离线请求的统一指标。 |
| [模型明细](normal-attempt1/02-model-breakdown.png) | 模型、供应商、上游名称及汇总。 |
| [请求列表](normal-attempt1/03-recent-requests.png) | 搜索、状态筛选和请求结果。 |
| [请求详情](normal-attempt1/04-request-details.png) | HTTP 200 与中断终态、未提供的 Token。 |
| [大字体统计](large-attempt2/06-statistics-large-font.png) | 320dp / 字体 1.5，指标改为单列，大数显示为 2.2B。 |
| [大字体趋势](large-attempt2/07-trend-large-font.png) | 当前滚动位置的趋势切换和柱形上部；后续内容继续纵向滚动。 |
| [大字体模型](large-attempt2/08-models-large-font.png) | 长模型名换行，供应商/上游摘要及指标。 |
| [大字体共享](large-attempt2/10-sharing-large-font.png) | 本机/局域网按钮等高，保留系统大字体。 |

常规 fixture 明确标为「离线测试」：四条请求，两条成功、一条失败、一条中断；只有一条返回输入 1,200、输出 180、合计 1,380 Token。大字体 fixture 为三条离线请求，其中一条合计 2,222,222,211 Token，用于检查大数与长名称布局。这些不是实际订阅账号用量。测试未请求外部模型。

## 分批结果

| 证据目录 | 代码版本 | 设备配置 | 结果 |
| --- | --- | --- | --- |
| [normal-attempt1](normal-attempt1/results.json) | `8f328a0` | 360dp，font_scale 1.0 | 6 项通过，325.942 秒。 |
| [large-attempt1](large-attempt1/results.json) | `8f328a0` | 320dp，font_scale 1.5 | 1 项失败，52.588 秒，导航文字布局越界断言。 |
| [navigation-final](navigation-final/results.json) | `5fcc65c` | 360dp，font_scale 1.0 | 修复后主导航 1 项通过，79.968 秒。 |
| [large-attempt2](large-attempt2/results.json) | `5fcc65c` | 320dp，font_scale 1.5 | 修复后大字体 1 项通过，110.171 秒。 |
| [restoration-final](restoration-final/results.json) | `713b151` | 360dp，font_scale 1.0 | 趋势恢复回归 1 项通过，35.342 秒，不生成截图。 |

各目录的 `instrumentation.log` 保存原始 runner 输出，`results.json` 记录逐例状态、测试前的 APK SHA、设备配置，以及当次截图时间、字节数与 SHA。通过用例全部为 `INSTRUMENTATION_STATUS_CODE: 0`，大字体专门运行没有按不匹配配置跳过。

五个统计流程检查模型下钻、供应商下钻保留七天范围、HTTP 200 中断详情、搜索/状态/清空确认，以及指标和趋势中的未知 Token 与真实零值区别。主导航检查共享首页、独立统计空态与系统返回。大字体用例检查真实 `MainActivity` 的导航文字无水平/垂直溢出、本机/局域网按钮等高，以及指标、趋势和模型明细可滚动到达。

状态恢复用例使用 `StateRestorationTester` 保存选中第六个时间桶的状态，销毁旧 composition 时将数据缩至一个桶，再恢复并检查请求读数、前后按钮禁用和 Token 切换。修复只限定恢复后的选中索引，不改变截图对应的常规布局；最后这版仅运行新增恢复回归，未重复未变的布局批次。

## 修复与异常证据

首次大字体运行报告 `Navigation label 共享 overflows horizontally`，正式 06–08 截图尚未生成。[失败后原图](large-attempt1/navigation-failure.png) 中导航文字肉眼完整，但严格的文字布局边界检查失败；同图还显示共享地址分段按钮因长文案换行而不等高。随后让导航标签占满其可用宽度并居中，将地址标签简为「本机 / 局域网」且统一按钮高度。复验使用原有系统字体大小和严格溢出断言。

主机没有 KVM。初次命令缺少 `ANDROID_AVD_HOME`，在 VM 启动前退出；补充实际 AVD 路径及 `ANDROID_I_WANT_MY_TCG=yes` 后启动一次无快照模拟器，后续各批使用同一 VM。准备阶段先后出现 [Quickstep](diagnostics/startup/02-quickstep-dialog.png) 和 [System UI](diagnostics/startup/03-system-ui-dialog.png) 无响应弹窗，均先实际查看，再各关闭一次。系统栏重启后的首张预热图曾为浅底白字；正式测试重新启动 Activity 后恢复深色，展示图状态栏均正常。启动诊断图单独归档。

## APK 来源

| 版本 | 包 | SHA-256 |
| --- | --- | --- |
| `8f328a0` | capture | `6470512915fe7b604c1e86e014815657b4fa257311020dece074b6a848cb59f7` |
| `8f328a0` | tests | `dddb9afec36d9416647034e3353441b9e292729069148516560b713f7a20b838` |
| `5fcc65c` | capture | `3f2902abc541032ed8d2685a51d73de51424ba6aa25ed777f5cd31177b1cb03e` |
| `5fcc65c` | tests | `0c70d7d2c980d86352ccc7f60e0279e795b12b9a62e5c596ef519b1bd2fb656b` |
| `713b151` | capture | `ccf6b38a1abdf2b98f39fb9b2bd52374678209077a1752a8e895983aead8df1b` |
| `713b151` | tests | `f91f4dded1a9437b4a61be64c04886d9612ee9e55f728a30dd939a8e229b1b09` |

Runner 为 `dev.pathpilot.app.capture.test/androidx.test.runner.AndroidJUnitRunner`。截图包独立使用 x86_64，常规交付 APK 仍仅 ARM64。各批在运行开始前记录 SHA，后续覆盖本地同名 APK 不改变已有证据归属。

## 清理

结束后恢复原始 density 320（清除覆盖）和 font_scale 1.0；清空 capture 与 androidTest 两个包的数据并停止本次 VM。正常产品包数据未操作。原始截图、日志、AVD 和构建产物保留。具体命令与结果见 [cleanup.json](diagnostics/startup/cleanup.json)。
