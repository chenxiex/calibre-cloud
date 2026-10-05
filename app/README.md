# Android 工程

当前工程提供本地 SAF 目录授权入口、应用完整副本读取和显式任务基础契约。任务契约说明见 [任务模块](src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)。OneDrive 登录、书库访问、任务队列、下载和阅读器尚未实现。

## 本地目录授权

点击“选择／重新授权目录”，在系统文件选择器中选择设备本地存储或 SD 卡目录。当前支持系统本地存储提供方；云端和未知提供方会被拒绝。取消重选保留原选择，重启后恢复持久授权；只读或失效时提示重新授权。

“目录已授权，书库待验证”只表示平台权限已保存；当前不检查或读取 Calibre 数据库，不导入书籍。通过“上一页／下一页”查看授权入口。

## 构建

在指定开发容器中，从项目根目录运行：

```bash
./gradlew --version
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

构建使用 JDK 21、Android SDK 36 和 Build Tools 36.0.0，最低支持 Android 11 / API 30。环境配置与设备连接方法见 [开发容器说明](../.github/.devcontainer/README.md)。依赖版本见 [版本目录](../gradle/libs.versions.toml)，实际解析版本见 [依赖锁](gradle.lockfile)。

## 构建产物

| 变体 | application ID | 名称 | APK |
| --- | --- | --- | --- |
| release | `io.github.chenxiex.calibrecloud` | Calibre Cloud | `build/outputs/apk/release/app-release-unsigned.apk` |
| debug | `io.github.chenxiex.calibrecloud.debug` | Calibre Cloud Debug | `build/outputs/apk/debug/app-debug.apk` |
| debug AndroidTest | `io.github.chenxiex.calibrecloud.debug.test` | 测试包 | `build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |

release 产物未签名。debug 使用独立包名和应用数据目录，可与正式版共存。

## OneDrive 配置

当前工程尚未读取 OAuth 属性或接入登录，AppAuth 回调接收器处于禁用状态。无注册配置也可构建；后续注册方式见 [OneDrive 应用注册](../README.md#onedrive-应用注册)。

构建结果、产物检查及未完成的验收见 [第一阶段验证记录](verification/phase-1.md)。面向 agent 的模块开发约束见 [AGENTS.md](AGENTS.md)。
