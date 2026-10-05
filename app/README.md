# Android 工程

当前实现对应步骤 01、R01、R04、R08 的缺配置可构建前提及 R34–R35：单 `:app` 模块和静态未配置页面。SAF 授权、OAuth 配置读取与登录、书库访问、队列、下载和阅读器均尚未实现；初始入口只展示说明，不发起源访问。

## 构建与依赖

使用项目根目录的 Gradle Wrapper，在指定开发容器中运行：

```bash
./gradlew --version
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

SDK 与持久工具数据目录见 [开发容器说明](../.github/.devcontainer/README.md)。构建 JDK 为 21，Java 和 Kotlin 编译目标为 17；最低 API 30，compile/target API 36，Build Tools 36.0.0。AGP 9.1.1 使用内置 Kotlin 2.2.10，Compose 编译器同版本；兼容依据见 [AGP 官方兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)。固定直接版本见 [版本目录](../gradle/libs.versions.toml)，实际传递版本见 [依赖锁](gradle.lockfile)。

普通构建严格校验依赖锁。锁生成任务解析应用及测试的 compile/runtime classpath；构建和 lint 同时记录实际使用的工具配置。仅有意更新依赖时执行以下命令，审阅版本与锁文件后重新执行不带 `--write-locks` 的构建及 lint：

```bash
./gradlew :app:resolveLockedDependencies :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --write-locks
```

JUnit、AndroidX Test 和 Compose UI 测试依赖及 runner 已配置。测试随实际功能加入，当前没有测试用例；测试任务无用例不表示功能测试通过。生产职责按包拆分；当前仅 `ui` 有实现，依赖容器在实际需要注入组件时手工构造，不引入 DI 框架；应用持久化使用原生 SQLite，WorkManager 自带的传递 Room 依赖不作为业务数据库。

## 安装隔离

| 变体 | application ID | 名称 |
| --- | --- | --- |
| release | `io.github.chenxiex.calibrecloud` | Calibre Cloud |
| debug | `io.github.chenxiex.calibrecloud.debug` | Calibre Cloud Debug |
| debug AndroidTest | `io.github.chenxiex.calibrecloud.debug.test` | 测试包（有实际用例时生成） |

release 当前生成未签名 APK。任何 ADB 安装前都必须核对实际 APK 中的 debug application ID；仅使用测试书库副本，记录结果后卸载 debug 与测试包，不能覆盖正式应用。后续书籍 provider authority 从 `${applicationId}.books` 派生；当前未开放 FileProvider。平台授权和墨水屏交互的真机验收留在对应步骤。

## 当前安全边界

AppAuth 的占位 scheme 分别为正式包名和 debug 包名加 `.disabled`；回调接收 Activity 显式禁用。当前不读取 `local.properties` 的 OAuth 属性，不发起登录；注册契约见 [根 README](../README.md#onedrive-应用注册)。缺配置构建无需 client ID 或 secret。

应用禁止明文流量和备份；授权实现时补齐凭据备份／迁移排除规则。当前合并 Manifest 移除依赖附带的网络、后台唤醒和前台服务权限；对应功能接入时再按实际路径申请。WorkManager 默认 initializer 已移除，没有注册 worker 或周期任务；不声明广泛存储权限。静态页面使用文字和布局，不添加滚动、过渡、加载动画或点击效果。

实际命令、产物检查及待验收项见 [第一阶段验证记录](verification/phase-1.md)。
