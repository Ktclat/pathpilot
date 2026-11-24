# 0.1.5 验证记录

日期：2026-11-24（UTC）。本轮区分同手机应用与局域网设备的入口，增加协议接入说明，并修复 Codex Chat 桥接丢失上游拒绝内容的问题。[0.1.4 记录](validation-0.1.4.md)保留。

## 构建与自动测试

最终代码执行：

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.5-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.5-arm64.apk
```

| 验证 | 结果 |
| --- | --- |
| 核心协议、供应商、OAuth 与代理 | 66 通过，含新增的拒绝 JSON/SSE 两项回归 |
| Android ViewModel、地址规则、服务状态与资源释放 | 22 通过 |
| 合计 | **88 通过，0 失败，0 跳过** |
| Android Lint | **0 错误，11 条既有警告** |
| APK | ZIP、签名与 ARM64 检查通过 |
| Actions 配置 | actionlint 通过 |

代码提交 `cf702b7` 的 [GitHub Actions](https://github.com/Ktclat/pathpilot/actions/runs/45444802147) 已成功，耗时 2 分 40 秒；测试、构建、Lint、ARM64 校验及产物上传均通过。

工具链与 0.1.4 相同。最终常规包为 `dev.pathpilot.app`，版本 `0.1.5` / versionCode 6，仅 `arm64-v8a`。本地交付副本 `artifacts/PathPilot-0.1.5-arm64.apk` 的 SHA-256：

```text
38e244a2e048d948e4d070d9c59819bbf78d277063c6b08600aa080f0ca43776
```

拒绝内容回归使用模拟 Responses 输出：JSON 保留 `message.refusal`，纯拒绝时 `content=null`；SSE 逐段输出 `delta.refusal`，不会因 done/completed 事件重复文本。这是对上游返回值的协议转换，没有新增本地内容筛选。

## Android 运行验证

使用 API 35 软件模拟器和独立 `dev.pathpilot.app.capture` 包，普通安装包仍只提供 ARM64。截图包 SHA-256：

```text
bf0316db0f6f0d405f4b8271d45c0df2710147fda21b058074ca4678410ccc1b
```

实际检查结果：

| 检查 | 结果 |
| --- | --- |
| 没有局域网候选地址时打开首页 | 默认选中本机应用，显示 `http://127.0.0.1:8317/v1`；地址与密钥在首屏可见 |
| 本机 HTTP | 设备内部访问 `127.0.0.1:8317/health` 返回 200 |
| 本机鉴权 | `/v1/models` 缺少 Authorization 或使用固定错误 Bearer 均返回 401 |
| 局域网地址选择 | 临时在 dummy0 添加两个实际内核 IPv4，菜单可选择 `.1` / `.2`，选中值显示正确 |
| 地址切换不重启服务 | 前后同 PID 2888、同监听 socket inode 28247；本机及两个测试 IP 的健康检查均返回 200 |
| 接入说明 | 实际打开并滚动到底，Base URL、Chat/Responses/models 完整路径、供应商范围均可读 |
| 关闭共享 | 同 PID 下 8317 不再监听、健康请求断开、PathPilot 唤醒锁释放 |

四张原始 PNG 和无凭据的 [HTTP/监听结果](../artifacts/screenshots/v0.1.5/runtime-results.json)保存在 [截图目录](../artifacts/screenshots/v0.1.5/README.md)。测试地址由 adb 临时添加到模拟器内核，没有写入产品代码。

主机无 KVM，使用软件模拟和安装后预编译。启动期 Quickstep 与 System UI 出现无响应弹窗，恢复窗口时重启过 capture 应用；以上 HTTP 与切换验证从恢复后的同一 PID 开始，未将重启前的界面计作运行验证。详细环境和清理记录见截图 README。

实际账号调用、指定第三方 Android 客户端和实体手机跨设备联调不计入本轮已通过项。客户端配置和已实现的协议范围见 [API 文档](api.md)。
