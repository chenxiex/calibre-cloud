# Android 工程

当前工程提供本地 SAF 目录授权入口、应用状态数据库、最小下载清单持久查询、应用完整副本读取和持久优先级任务队列。任务契约说明见 [任务模块](src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)。OneDrive 已接入个人账号浏览器授权和私有加密状态保存；真实登录验收状态见验证记录。队列与单执行协调器已实现，当前只由注入处理器验证协议；书库访问、下载、后台驱动、任务页和阅读器尚未实现。

## 本地目录授权

点击“选择／重新授权目录”，在系统文件选择器中选择设备本地存储或 SD 卡目录。当前支持系统本地存储提供方；云端和未知提供方会被拒绝。取消重选保留原选择，重启后恢复持久授权；只读或失效时提示重新授权。

目录选择成功后保存当前候选位置与选择代号；取消保留原配置，重选保留旧书库绑定和副本。最小清单和副本读取已接入同一个应用状态库，但尚无实际下载或清单界面。

“目录已授权，书库待验证”只表示平台权限已保存；当前不检查或读取 Calibre 数据库，不导入书籍。通过“上一页／下一页”查看授权入口。

## 构建

使用 JDK 21、Android SDK 36 和 Build Tools 36.0.0；最低支持 Android 11 / API 30。环境与设备连接见 [开发容器说明](../.github/.devcontainer/README.md)。

在项目根目录运行：

```bash
./gradlew --version
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

默认资源参数见 [gradle.properties](../gradle.properties)：worker 上限 4、JVM 可用处理器数 4、堆上限 2 GiB。临时降低资源占用：

```bash
./gradlew :app:assembleDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2g -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8'
```

依赖版本见 [版本目录](../gradle/libs.versions.toml)，实际解析版本见 [依赖锁](gradle.lockfile)。

## 构建产物

| 变体 | application ID | 名称 | APK |
| --- | --- | --- | --- |
| release | `io.github.chenxiex.calibrecloud` | Calibre Cloud | `build/outputs/apk/release/app-release-unsigned.apk` |
| debug | `io.github.chenxiex.calibrecloud.debug` | Calibre Cloud Debug | `build/outputs/apk/debug/app-debug.apk` |
| debug AndroidTest | `io.github.chenxiex.calibrecloud.debug.test` | 测试包 | `build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |

release 产物未签名。debug 使用独立包名和应用数据目录，可与正式版共存。

## OneDrive 配置

工程读取根目录被忽略的 `local.properties`，保留 `sdk.dir` 等已有属性。微软注册步骤与无凭据模板见 [OneDrive 应用注册](../README.md#onedrive-应用注册)。三项属性全部填写后才启用配置：

| 属性 | 用途 |
| --- | --- |
| `onedrive.clientId` | 两变体共用的开发者注册标识 |
| `onedrive.redirectUri` | release 完整回调 URI |
| `onedrive.debugRedirectUri` | debug 完整回调 URI |

正式版和 debug 版推荐使用各自独立的小写自定义 scheme，并与微软注册的 URI 完全一致；不需要 client secret。

缺少任一项时仍可构建和使用本地授权，OneDrive 页面显示配置指引。完整配置非法或回调范围重叠时，按构建错误中指出的属性修正。配置有效后点击“登录／重新登录”，在系统浏览器中使用个人微软账号授权。返回应用后显示授权结果；返回时未完成授权，可点击“取消登录”再重试。请求十分钟后失效。已授权时可显式刷新授权；凭据恢复失败会要求重新登录。

“个人 OneDrive 已授权”只表示 OAuth 授权完成，尚未选择或验证书库。令牌和进行中的登录请求通过 Android Keystore 加密保存，重启可恢复，不进入书籍 provider、普通缓存或备份。

从根目录验证配置矩阵：

```bash
python3 scripts/verify-oauth-config.py
```

结果与日志保存在被忽略的 `.oauth-verification/` 中。配置校验、回调匹配和授权实现约束见 [授权模块 AGENTS.md](src/main/java/io/github/chenxiex/calibrecloud/auth/AGENTS.md)。

构建结果、产物检查及未完成的验收见 [第一阶段验证记录](verification/phase-1.md)。应用状态与最小清单的实现及验证见 [第二阶段验证记录](verification/phase-2.md) 和 [状态库约束](src/main/java/io/github/chenxiex/calibrecloud/state/AGENTS.md)。面向 agent 的模块开发约束见 [AGENTS.md](AGENTS.md)。
