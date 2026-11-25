# 页面与交互

采用官方 Material 3 Expressive 1.5.0 和默认形状、排版、动效；Android 12+ 使用系统 Monet，旧版使用统一备用配色。

0.1.3 在来源选择、供应商列表和配置标题中使用 OpenAI/ChatGPT 结形标记与 DeepSeek 鲸鱼标记；自定义兼容来源保留中性服务器图标。素材在本地矢量绘制，来源和许可证见 [第三方说明](third-party.md)。

## 四个入口

| 页面 | 内容与操作 |
| --- | --- |
| 共享 | 总开关、服务状态、本机/局域网 API 地址、密钥、接入说明、请求记录；未配置模型时另有配置入口 |
| 供应商 | 来源列表、启停、编辑、Codex 登录、获取模型、添加路由 |
| 路由 | 客户端别名、供应商及上游模型、启停、添加和编辑 |
| 设置 | 监听端口、上游代理、重置密钥、系统电池设置、说明与版本 |

移除首页口号、重复标题、三张大统计卡、路由页常驻协议说明及设置页隐私宣告。列表改用 LazyColumn，网络接口枚举、共享服务读取凭据与启动监听在 IO 协程执行。响应性与实际运行结果见验证记录。

0.1.4 总开关表示实际服务状态，配置入口与启停分开；即使尚未配置模型也可开启监听。启动过程中允许关闭，停止释放资源期间显示「正在关闭」并暂时禁用开关。

0.1.5 API 卡片使用 Material 分段按钮区分「本机应用」与「局域网设备」。默认提供本机 `127.0.0.1`，无需等局域网扫描；局域网侧优先选择 Wi-Fi/有线网络，多个 IPv4 地址可从菜单切换。保留未被系统识别类型的热点/USB 等接口，并显示原接口名；VPN 与蜂窝地址仅在局域网侧按需展开。页面前台与运行中的服务每 5 秒刷新，所选地址消失后回到剩余候选。选择仅影响显示和复制，同一服务的端口、访问密钥与模型路由共用；不表示仅允许选中的网络接入。

总开关改名「模型共享」，同时服务本机和局域网；地址与密钥放在配置入口和统计之前。点击「OpenAI 兼容 · 接入说明」打开原生底部面板，展示当前 Base URL、API Key/模型填写方式、Chat Completions/Responses/模型列表的完整端点及供应商支持范围。Base URL 和端点均可单独复制；未发现局域网地址时只显示相对路径，不生成假的可复制地址。

## 供应商

点击来源直接进入表单。API 来源按名称、Key、地址排列，模型标题旁提供「获取模型」，仍可手动填写。字段只在发生错误时说明原因并定位到输入框。已有路由保留，新选择的模型随供应商一起原子保存；同一来源/模型不重复创建，别名冲突自动加后缀。

DeepSeek 需要 API Key；自定义 OpenAI 兼容来源允许 HTTP/HTTPS，无认证服务可留空 Key。Codex 使用独立订阅登录流程，编辑名称不清除已有授权。

0.1.6 补全 Codex 模型发现：已登录的供应商卡片和编辑页均提供「获取模型」，登录成功后自动打开编辑页并读取订阅目录。结果可筛选、多选，保存时使用既有原子路由创建逻辑。新建供应商先「保存并登录」；已保存未登录时展示登录操作。获取期间可取消，失败后可重试，空目录明确显示；重新登录会清除旧账号的目录选择，保留手动输入。订阅凭据由 AppStore 在 IO 上读取并按需刷新，不进入表单/API Key 字段；目录请求使用当前出站代理。

不再全局禁止截图。密钥默认掩码、显隐与敏感剪贴板标记保留；凭据继续由 Android Keystore 加密保存。删除供应商和重置局域网密钥仍显示具体后果确认。

新共享密钥使用 `sk-` 前缀，首页在掩码状态下只展示前缀，随机部分继续隐藏。升级保留已有密钥；手动重置后生成新格式。

## 代理

默认跟随系统，适合手机 VPN/TUN。HTTP/SOCKS5 可指定本地或远程无认证代理主机与端口，覆盖模型获取、生成和 App 内 Codex 授权/刷新。外部浏览器的登录页仍使用浏览器网络。接口细节见 [API 文档](api.md)。

## 参考依据

只参考交互组织，未复制参考项目代码：

- RikkaHub [紧凑来源选择](https://github.com/rikkahub/rikkahub/blob/2435dbdf4690fe26d424b8d1ab778bcde9c6cd75/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderSheets.kt#L59) 与 [名称/密钥/地址表单](https://github.com/rikkahub/rikkahub/blob/2435dbdf4690fe26d424b8d1ab778bcde9c6cd75/app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt#L248)。
- CC Switch [模型输入与获取合并](https://github.com/farion1231/cc-switch/blob/e6fd4accd257406bf9141baa402caaa78f975ba1/src/components/providers/forms/shared/ModelInputWithFetch.tsx#L31) 与 [按需渲染字段提示](https://github.com/farion1231/cc-switch/blob/e6fd4accd257406bf9141baa402caaa78f975ba1/src/components/providers/forms/ApiKeyInput.tsx#L76)。
- [Material 3 1.5.0 官方源码](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.5.0/material3-1.5.0-sources.jar)。

协作规则写入根 [AGENTS.md](../AGENTS.md)，适用于 Astra 等所有开发代理。测试和截图结果见 [验证记录](validation.md)。
