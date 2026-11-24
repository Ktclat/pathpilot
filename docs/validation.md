# 0.1.3 验证记录

日期：2026-11-24（UTC）。本轮补齐供应商品牌图标，并将新共享密钥改为 `sk-` 格式。[0.1.2 历史记录](validation-0.1.2.md)保留。

## 构建与自动测试

执行核心测试、应用单元测试、Debug APK 构建和 Android Lint；修正新增图标组件的 Modifier 默认值后，再次通过应用测试、构建和 Lint。

| 验证 | 结果 |
| --- | --- |
| 核心协议、供应商、OAuth 与代理 | 64 项通过 |
| 供应商编辑 ViewModel | 9 项通过 |
| 总计 | **73 通过，0 失败，0 跳过** |
| Android Lint | **0 错误，11 条既有警告** |
| 包身份及架构 | `dev.pathpilot.app`，仅 `arm64-v8a` |
| 版本 | `0.1.3` / versionCode 4 |
| APK | ZIP、签名、ABI 检查通过 |
| 品牌素材 | 原始 SVG、来源说明、完整 MIT 许可证已包含在 APK assets 中 |
| Actions 配置 | actionlint 通过 |

新增回归验证新密钥的 `sk-` 格式、完整 Bearer 匹配、已有 `pp_` 密钥继续使用，以及手动轮换后旧密钥失效。随机部分保持 32 字节（256 位）；没有自动重置用户现有密钥。

工具链与 0.1.2 相同。Lint 剩余项为目标 SDK、依赖版本和 WakeLock 释放/超时建议。

代码提交 `9086237` 的 [Android ARM64 Actions](https://github.com/Ktclat/pathpilot/actions/runs/45430812460) 已成功，测试、Lint、签名与 ABI 检查、APK 和报告上传均通过。

本地交付包：`artifacts/PathPilot-0.1.3-arm64.apk`。SHA-256：

```text
4d54e94495fc7ba1e4782ced86718dc6a3c6778f4c7c7faece79614ee7837b20
```

## 第三方客户端格式验证

实际使用官方 OpenAI Python SDK **3.58.0**，向本机 HTTP 模拟端点发起请求：

- `sk-` + 64 位 hex、旧 `pp_` + 64 位 hex、普通合成令牌，各验证一次模型列表、Chat Completions、Responses。
- **9 次请求全部通过**，服务器核对 Authorization 与配置的完整 Bearer 字符串一致。
- 请求使用自定义 Base URL 和合成数据，无远程推理或真实凭据。该结果验证 SDK 的地址/密钥传输行为；PathPilot 路由鉴权另由上述核心测试覆盖。

SDK 实现和固定源码链接见 [API 文档](api.md#访问密钥)。其他客户端自己的输入校验与接口支持需按具体产品核对。

## UI 验证

本轮截图使用独立 `dev.pathpilot.app.capture` 包，在 Android 15 / API 35 软件模拟器中运行；正常交付包仍仅支持 ARM64。截图、实际操作与构建哈希见 [0.1.3 截图记录](../artifacts/screenshots/v0.1.3/README.md)。

已检查首页实际显示 `sk-` 掩码，以及来源页的 OpenAI/ChatGPT 结形标记、DeepSeek 蓝色鲸鱼和自定义来源的通用图标。使用占位 Key 保存 DeepSeek 后，列表与配置标题均显示其品牌标记。系统启动阶段曾出现 System UI 无响应及黑屏，恢复后采集原始截图；完整运行记录见截图说明。

真实账号调用、实体手机 VPN 出口和设备间 Wi-Fi 互访不属于本轮验证结果。
