# 统计页 UI 审查

日期：2026-11-25。范围为共享 → 统计概览 → 供应商/模型明细 → 请求详情，以及 320dp、1.5 倍系统字体下的同一界面。参考 [CC Switch 源码中的统计组织方式](statistics-reference.md)，使用原生 Compose、Material 3 Expressive、Monet 和系统无衬线字体。

## 截图起点

用户提供的当前共享页截图包含共享开关、地址、密钥、大号「今日用量」卡和最近请求。源码中「今日用量」与其他卡片标题采用不同字体角色，「未提供」又与数字一起使用 headlineSmall。没有发现该卡单独引入其他字体文件。问题是信息层级和字体角色不一致，同时共享配置被统计内容拉长。

## 逐步审查结果

1. **共享：通过。** [完整首页](../artifacts/screenshots/v0.1.9/navigation-final/00-sharing-home.png)的标题字体和字重协调；移除大用量卡，底部提供独立统计入口。地址和密钥继续使用便于逐字符辨认的等宽字体。最近请求仅在有记录时显示最近两条。
2. **统计概览：通过。** [完整主页面](../artifacts/screenshots/v0.1.9/navigation-final/09-statistics-main.png)的五项导航完整，统计正确选中。四项指标共用数字样式；[空状态](../artifacts/screenshots/v0.1.9/navigation-final/05-statistics-empty.png)用请求数 0、其他未知指标「—」区分无记录和真实零值。部分 Token 覆盖提示位于指标下方。
3. **趋势与模型：通过已检查区域。** [模型明细](../artifacts/screenshots/v0.1.9/normal-attempt1/02-model-breakdown.png)中供应商、上游模型、已知 Token 和耗时分层清楚；选中时间段给出具体日期和数值。趋势零值不画正高度柱，缺失 Token 保留未知。供应商和模型下钻保留日期及模型三元组范围，已由设备测试验证。
4. **请求与详情：通过。** [请求列表](../artifacts/screenshots/v0.1.9/normal-attempt1/03-recent-requests.png)的搜索、状态和请求摘要整齐；[请求详情](../artifacts/screenshots/v0.1.9/normal-attempt1/04-request-details.png)分别展示 HTTP 200 与中断结果，缺失 Token 使用正文大小，没有大号缺失提示。搜索、状态筛选和清空确认均通过设备测试。
5. **大字体：修正后通过。** [共享](../artifacts/screenshots/v0.1.9/large-attempt2/10-sharing-large-font.png)的本机/局域网按钮等高，地址完整换行且复制按钮可见；[概览](../artifacts/screenshots/v0.1.9/large-attempt2/06-statistics-large-font.png)改单列，长数字以 2.2B 等简写显示；[长模型](../artifacts/screenshots/v0.1.9/large-attempt2/08-models-large-font.png)可换行，上游副标题按卡片摘要保留省略号，完整内容在请求详情中查看。日期范围可以横向滚动，露出的部分选项提示仍有内容。五个导航标签的横纵向溢出断言和地址按钮等高断言均通过。

## 本轮确实发现并修正的问题

| 发现 | 处理与复验 |
| --- | --- |
| 原共享页字体角色不统一，统计和连接设置混在长页面内。 | 独立统计入口、统一字体角色和数字样式，正常与大字体截图已实看。 |
| [首轮大字体](../artifacts/screenshots/v0.1.9/large-attempt1/navigation-failure.png)的地址按钮因长标签换行而高低不齐。 | 文案简为「本机 / 局域网」，按钮等高；第二轮实际截图和等高断言通过。 |
| 首轮导航触发 `didOverflowWidth`，当时截图中文字视觉完整。 | 标签使用完整项宽并居中，保持系统字体缩放与严格断言；第二轮五个标签均无溢出。没有把首轮断言夸大为已观察到的截字。 |
| 源码终审发现恢复旧趋势选中索引时，新时间范围可能只有更少的桶。 | 统一以有效索引读取、突出显示和切换时间段；新增真实保存/恢复回归，结果见验证记录。 |

启动准备阶段出现过 Quickstep、System UI 无响应弹窗，已保留诊断图。正式截图均无遮挡，状态栏恢复为与浅色背景匹配的深色图标，没有为启动时的暂态白字增加主题补丁。

## 证据范围

以上原始 PNG 均来自 Android 模拟器实际运行，未编辑像素。00/05/09、06/07/08/10 是生产 MainActivity；01/02/03/04 是直接挂载生产统计组件的测试 Activity。离线请求数字不代表真实账号用量。各批次 APK、源代码提交、日志与校验和见[截图记录](../artifacts/screenshots/v0.1.9/README.md)。

[大字体趋势图](../artifacts/screenshots/v0.1.9/large-attempt2/07-trend-large-font.png)显示当前滚动位置的切换控件和柱顶，轴线及选中读数在视口外，未将这一张图算作完整趋势读数的视觉验收。截图与布局断言检查了当前画面的截断、重叠、选中状态和可达性；本轮没有执行 TalkBack 全流程、逐页深色模式或完整无障碍认证。
