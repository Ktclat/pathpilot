# 界面与供应商配置

## 主题与图标

使用 AndroidX Material 3 **1.5.0** 的公开 `MaterialExpressiveTheme`，保留官方默认形状、排版和 Expressive 动效。页面组件通过 `MaterialTheme` 读取颜色角色，不再为单个按钮或导航固定品牌色。

- Android 12+：`dynamicLightColorScheme` / `dynamicDarkColorScheme` 读取系统 Monet 配色，跟随壁纸/系统调色板；明暗模式跟随系统。
- Android 8–11：使用完整备用色板，覆盖普通、容器、表面和 fixed 角色，避免混入另一套默认色。
- 启动窗口、状态栏、导航栏随明暗主题调整。图标统一为 Material 线框风格，PathPilot 的 P 形路径标识用于页面标题、启动器与共享通知。
- 提供自适应图标和 Android 13+ monochrome 图层；主题图标是否启用由系统启动器设置决定。

依赖使用 Compose BOM `2026.09.00` 的 1.12.1 基础组件，并明确指定 Material 3 `1.5.0`。官方 AAR 元数据要求 compile SDK 37 / AGP 至少 9.1.0；项目使用支持 SDK 37 的 AGP 9.1.1，因此工具链同步升级；minSdk 26、targetSdk 35 和 ARM64 交付范围保持不变。

一手依据：[Material 3 1.5.0 官方源码包](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.5.0/material3-1.5.0-sources.jar)、[官方 Android 组件包](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.5.0/material3-android-1.5.0.aar)、[Compose 动态配色文档](https://developer.android.com/develop/ui/compose/designsystems/material3#dynamic_color)。版本能力已通过前两个实际发布文件核实。

## 添加与编辑供应商

1. 选择 ChatGPT / Codex、DeepSeek 或 OpenAI 兼容 API。
2. 配置名称；API 类型填写 HTTPS 基础地址与 Key。Codex 使用订阅授权，不要求 API Key。
3. 可选读取 `/models`，或手动填写模型 ID。DeepSeek 常用模型仅是未验证建议。读取成功说明模型列表接口可访问，不等同于已验证推理能力。
4. 保存供应商与所选模型的路由。已有供应商/模型组合不重复添加，也不会删除原路由；共享别名冲突自动使用 `-2` 等后缀。未选择模型可先保存，再从供应商卡片添加路由。
5. 新 Codex 来源保存后进入设备授权；编辑已有 Codex 不清除授权。重新登录入口保留在供应商卡片。

来源切换分别保留内存草稿，旋转屏幕不丢失草稿；未保存退出有确认。API Key 默认隐藏，使用密码键盘，不写入 Bundle 或 SavedState。保存沿用 Android Keystore 加密和原子写入；删除同时移除该来源凭据与关联路由。

测试连接不发起生成请求，最长 10 秒，不跟随重定向，不自动重试；模型列表限制 1 MiB / 500 个唯一模型。错误信息不回显服务器原始正文或凭据。取消、切换来源和修改地址/密钥会取消旧请求并忽略迟到结果；同一配置重试失败会保留此前模型选择，并标注本次未验证。供应商不支持 `/models` 时仍可手动配置和保存。

实际构建、测试及模拟器结果见 [验证记录](validation.md)。
