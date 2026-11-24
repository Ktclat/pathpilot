# 0.1.4 验证记录

日期：2026-11-24（UTC）。本轮新增多 IPv4 地址选择、网络分类与共享总开关，并研究后续 MCP 范围。[0.1.3 历史记录](validation-0.1.3.md)保留。

## 构建与自动测试

最终源代码执行：

```bash
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py artifacts/PathPilot-0.1.4-arm64.apk
$ANDROID_HOME/build-tools/36.0.0/apksigner verify artifacts/PathPilot-0.1.4-arm64.apk
```

| 验证 | 结果 |
| --- | --- |
| 核心协议、供应商、OAuth 与代理 | 64 项通过 |
| 供应商编辑 ViewModel | 9 项通过 |
| 地址合并/分类 | 7 项通过 |
| 服务状态与完整资源释放 | 6 项通过 |
| 合计 | **86 通过，0 失败，0 跳过** |
| Android Lint | **0 错误，11 条既有警告** |
| 版本与架构 | `0.1.4` / versionCode 5，`dev.pathpilot.app`，仅 `arm64-v8a` |
| APK | ZIP、签名与 ABI 检查通过 |
| Actions 配置 | actionlint 通过 |

地址回归包括 Wi-Fi/有线优先、VPN 优先于其底层 transport、同接口多个 IP 继承类型、同 IP 不同接口保留、去重/自然排序、非 RFC1918 本机地址保留。状态回归包括重复启动、启动中取消、停止期间拒绝重启和迟到更新。

真实 ServerSocket 回归在独立 IO/UI dispatcher 间切换并取消拥有者，断言监听已关闭、新连接被拒绝，完成状态发生在所有释放之后；另验证一项释放异常时仍执行剩余释放。

工具链与 0.1.3 相同。Lint 剩余项为目标 SDK、依赖版本和 WakeLock 释放/超时建议。

代码提交 `5c1b3ee` 的 [GitHub Actions](https://github.com/Ktclat/pathpilot/actions/runs/45440622128) 已成功，耗时 2 分 46 秒；测试、构建、Lint、ARM64 校验和产物上传均通过。

本地交付包：`artifacts/PathPilot-0.1.4-arm64.apk`。SHA-256：

```text
cd8ee32083e3242a9707029edce584c669cc90538d1c8dbe59a0119fa6c8e2d3
```

## Android 运行验证

使用 API 35 软件模拟器与独立 `dev.pathpilot.app.capture` 包。通过 adb root 在现有 dummy0 接口临时添加 `192.168.50.1/24` 与 `.2/24`，用于检验实际内核多地址枚举；这些地址没有写入产品代码或截图像素。系统将 eth0 的 `10.0.2.15` 识别为 CELLULAR，页面据此归入其他网络。

初次包实际通过了地址选择、空模型启动、两个私网 IP 的 `/health` 返回 200、移除所选地址后自动回退。随后发现真实关停失败：UI 已关闭、Android 服务已销毁，但同一 capture 进程仍监听 8317，新的健康请求继续成功。

根因是清理段首个 `withContext(NonCancellable + Main)` 返回已取消 IO 上下文时抛出取消，跳过了 listener 和锁释放，完成兜底又提前更新关闭状态。修复将整个释放序列置于不切换 dispatcher 的 `withContext(NonCancellable)` 内，并把兜底限定为协程 body 从未进入的情况。

最终截图包 SHA-256 为 `ee8789903b6b5afda8c7b77e6e032b81974cb727e630c2903b06afde5f07162b`。在同一进程（PID 3082）中完成两轮开启、关闭复验：

| 阶段 | 监听与健康检查 | 唤醒锁 |
| --- | --- | --- |
| 初始关闭 | 8317 无监听；转发的健康请求无法连接 | 无 PathPilot 锁 |
| 第一次开启 | 两个本机 IPv4 地址及 adb 转发的 `/health` 均返回 200 | 持有 `PathPilot:LanRouter` |
| 第一次关闭 | 8317 无监听；转发的健康请求无法连接 | PathPilot 锁已释放 |
| 第二次开启 | 相同端口成功重用；两地址及转发健康请求均返回 200 | 重新持有 PathPilot 锁 |
| 第二次关闭 | 8317 无监听；转发的健康请求无法连接 | PathPilot 锁已释放 |

最终包还验证了关闭共享时的地址刷新：选择 `.2` 后移除该地址，页面自动回退 `.1`，多地址入口随之消失，不需重新开启服务。三张 [实际运行截图及测试条件](../artifacts/screenshots/v0.1.4/README.md)一并保存。界面地址选择只改变展示与复制内容，两个地址都由同一监听器提供服务。

MCP 本轮仅研究并写入 [后续范围](mcp-roadmap.md)，未集成 MCP SDK 或提供 MCP 端点。真实多网络手机、VPN 入站互访及真实账号调用不计入本轮验证结果。
