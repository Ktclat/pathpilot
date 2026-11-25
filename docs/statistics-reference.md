# 移动端统计页的参考依据

核查日期：2026-11-25。本次直接读取 CC Switch 官方仓库源码，固定提交为 `bf4cc69fe00e762a17d2ee40f409640cde8cef15`；这是源码研究，不是已运行其桌面应用的视觉验收。仅借鉴信息结构与交互，PathPilot 继续使用原生 Compose、Material 3 Expressive 和 Monet。

Magpie 的具体仓库仍未由现有资料唯一确认，因此本轮不把同名项目当作已核实参考。CC Switch 已提供足够的统计组织依据，不影响当前实现。

## CC Switch 实际如何组织

| 源码中已核实的做法 | 对应文件 |
| --- | --- |
| 全局独立「用量统计」页，组织为范围栏、概览、趋势、明细；明细切换请求日志、供应商、模型。 | [UsagePage][page]、[UsageDashboard][dashboard] |
| 时间、供应商和模型范围贯穿汇总、趋势和明细；供应商范围下继续选择具体模型。 | [UsageDashboard][dashboard] |
| 指标共用数字样式，窄容器采用两列；点击指标切换图表显示内容，次级 Token 明细可展开。 | [UsageHero][hero] |
| 趋势一次只画一个指标；短范围按小时、较长范围按日。横轴使用完整时间键，避免跨年同名日期混淆。 | [UsageTrendChart][trend] |
| 供应商、模型明细按请求数降序；桌面表格可横向滚动，并支持分页。 | [ProviderStatsTable][providers]、[ModelStatsTable][models] |
| 请求行进入详情；日志有状态与范围过滤，详情区分基本信息、Token、费用和性能。 | [RequestLogTable][logs]、[RequestDetailPanel][detail] |
| 加载、无请求、真正的零值分别处理；无数据时指标使用「—」，而不是把所有项目填成 0。没有分母的命中率同样使用「—」。 | [UsageHero][hero] |
| 今日从本地午夜起算，7/14/30 天按本地自然日向前取范围；全部与自定义日期另设。 | [usageRange][range] |

CC Switch 的指标使用统一 `text-metric` 角色（24px、32px 行高、600 字重）及等宽数字特性，正文使用系统无衬线字体栈；这证明可用一套字体角色保持一致，不需要给每张卡片选择不同字体。[字体角色定义][typography]

## 转成手机原生界面

建议将「统计」作为单独主入口，共享页集中显示共享开关、接入地址和密钥。统计页使用「概览 / 请求」切换，避免让连接配置与分析面板混在同一长页面。

1. **概览**：先选时间范围；用紧凑的两列指标显示请求数、已知 Token、成功率和平均耗时。数字、单位、缺失占位符使用同一套排版；「未提供」的解释放在辅助文字或统计说明中，避免大字缺失提示占据主视觉。
2. **趋势**：提供请求 / Token 两个指标切换；今日按小时、近七天按日。柱状或折线均可，统一使用当前范围，点击或辅助文字给出精确值。仅有未知 Token 的时间段应保留未知，不能画成真实零消耗。
3. **供应商与模型**：手机使用可点开的列表，先按供应商或模型查看汇总，再进入对应请求；保留模型三元组的区分，避免共享名相同但供应商或上游不同的历史合并。沿用原有模型管理页的供应商分层。
4. **请求**：保留搜索、结果筛选和底部详情面板。列表只放模型、供应商、时间、结果和耗时，Token 明细、接口及固定错误码放进详情，不把桌面宽表横搬到手机。
5. **空状态和字体**：没有请求时用简洁空状态；真实的零值与未取得的字段分开。全页沿用系统无衬线字体和 Material 字体角色，等宽字体仅用于地址或需要逐字符辨认的 ID。验证正常与放大字体下的换行、数字、筛选和导航，不能只检查截图默认字号。

这些建议不要求复制 CC Switch 的热力图、应用日志扫描、汇率、桌面列配置或定价面板。当前的手机任务是查看路由请求及其真实用量，优先保证概览、趋势和请求详情之间的统计范围一致。

## 本项目的数据口径

- PathPilot 记录来自手机转发的请求，保留最新 1,000 条。今天、七天与全部均是保留记录内的统计；界面应能查看这个范围说明。
- 已有数据可直接支持请求数、结果分布、耗时、供应商/模型汇总，以及上游实际返回的 Token。没有请求时成功率和平均耗时没有有效分母，应显示缺失占位符。
- 未取得 Token 时保持未知。当前观测器对超过 4 MiB 的 JSON/单个 SSE 事件会跳过该段用量解析，不能把所有未知都归因为供应商没有返回。
- **不套用 CC Switch 的 Token 加法**：其类型与注释明确采用归一化后的新增输入、输出、缓存写入、缓存读取口径。PathPilot 当前 `cachedInputTokens` 是输入的子集，`reasoningOutputTokens` 是输出的子集，两者不能再次加进 total。[CC Switch 统计类型][types]
- 当前没有模型价格、实际账单、首 Token 时间和生成阶段耗时，不生成费用、TTFT 或输出速度数字。订阅剩余额度也不由本地请求 Token 推算。

[page]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/usage/UsagePage.tsx
[dashboard]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/usage/UsageDashboard.tsx
[hero]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/usage/UsageHero.tsx
[trend]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/usage/UsageTrendChart.tsx
[providers]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/usage/ProviderStatsTable.tsx
[models]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/usage/ModelStatsTable.tsx
[logs]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/usage/RequestLogTable.tsx
[detail]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/components/usage/RequestDetailPanel.tsx
[range]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/lib/usageRange.ts
[typography]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/tailwind.config.cjs#L206
[types]: https://github.com/farion1231/cc-switch/blob/bf4cc69fe00e762a17d2ee40f409640cde8cef15/src/types/usage.ts#L73
