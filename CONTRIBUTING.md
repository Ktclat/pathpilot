# 贡献指南

先阅读 [AGENTS.md](AGENTS.md) 中的协作、代码与文案约定。模块职责见 [架构](docs/architecture.md)，已完成与待验证范围见 [项目状态](docs/project-status.md)。

## 开发与检查

使用仓库 Gradle Wrapper。工具链版本由构建文件和 [CI SDK 脚本](scripts/setup-ci-android.sh) 固定；初次配置见 [README](README.md#构建)。

代码变更完成后，按影响范围执行仓库检查：

```sh
./gradlew --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
python3 scripts/verify-apk.py app/build/outputs/apk/debug/app-debug.apk
```

同时验证 APK 签名；工作流变更使用 `actionlint` 检查。协议改动优先覆盖真实回环 HTTP 请求、响应和取消行为，Android 持久化或导航行为使用相应设备测试。已通过的检查仅在后续改动或新发现影响结论时重跑。

## UI 变更与截图

每轮 UI 改动都要构建、安装并实际运行受影响流程，审阅模拟器或实体设备原始截图。检查内容至少覆盖本轮变化涉及的主导航、返回、弹窗和键盘；主题、语言与大字体按影响补充。

云端 x86_64 模拟器使用隔离截图包：

```sh
./gradlew --no-daemon -PuiCapture=true :app:assembleDebug :app:assembleDebugAndroidTest
```

包名为 `dev.pathpilot.app.capture`，与常规 ARM64 应用分开。构建截图包前先另存常规 APK；相同输出路径会被后续构建覆盖。设备流程与命令见 [Android 测试说明](app/src/androidTest/README.md)。

截图和验证记录放在 `artifacts/screenshots/<版本或批次>/`，记录源码提交、APK SHA-256、系统版本、屏幕密度、字体大小和逐例结果。失败日志与图片保留，重试另开批次。模拟数据需在记录中说明，不能描述为真实账号验证。测试后恢复设备设置并清理隔离包数据。

## 文档维护

- `README.md` 保留使用与开发入口；协议细节分别维护在 `docs/api.md` 和 `docs/mcp-roadmap.md`。
- 模块边界或数据流程变化更新 `docs/architecture.md`，实现与验证状态更新 `docs/project-status.md`。
- 构建、CI 与设备结果写入 `docs/validation.md` 并链接原始证据；历史验证归档保留原版本信息。
- 公共标签和交互同步更新中文、英文资源与实际引用页面，不在多个文档重复维护一套版本事实。

## 版本与分发

当前版本采用 `0.0.1-<commit>`，其中 `commit` 是实际构建检出的 Git 提交前 7 位；构建需要保留 Git 元数据。Android `versionCode` 独立递增，新增对外分发版本时高于此前版本，不随显示版本或提交后缀重置。设置中的版本读取 `BuildConfig.VERSION_NAME`，打包脚本读取实际 APK 版本，不另造版本来源。

发布 APK 应从已提交的源码构建；本地未提交工作树的提交后缀只标识其基线。APK、校验文件和发布标签必须对应同一次成功构建，不能用旧 APK 搭配新提交说明。

持续覆盖安装要求同一签名密钥和递增的 `versionCode`。当前工作流生成 debug 签名开发包；使用临时 Runner 密钥的产物可能无法覆盖前一版本，正式持续分发需配置长期签名。

## 提交与交付

将导航、协议、存储或构建等不同目的的修改拆成可审阅提交。提交说明写明问题和最终行为，验证记录区分本地通过、CI 通过、已上传产物与已发布 Release。

及时推送当前任务授权的分支，并跟进对应 CI。提交前检查差异，不包含工具链、构建缓存、私钥、真实凭据或测试产生的用户配置。发布遵循 [Actions 工作流](.github/workflows/android.yml)：手动运行并启用 `publish_prerelease` 时，从实际 APK 版本自动生成 `v<版本>` 标签，无需另填标签。也可推送同格式标签触发验证后预发布；标签提交必须属于 `main`，且标签与构建出的 APK 版本一致。已有发布不会被覆盖。
