# 第一阶段验证记录

## 步骤 01：Android 工程与构建隔离

执行日期：2026-10-05。覆盖 R01、R04 的工程基础、R08 的缺配置可构建前提、R34–R35 的构建与隔离约定。代码和自动检查完成；用户于 2026-10-05 确认提交步骤 01，未开展步骤 02。

### 实现范围

单 `:app` 模块、Kotlin DSL、版本目录、Gradle Wrapper 和严格依赖锁；静态 Compose 未配置入口；正式／debug／AndroidTest 独立标识。没有 SAF 授权、OAuth 配置读取、登录、源文件访问、任务执行或 FileProvider。页面仅文字与布局，不显示虚构成功，不包含滚动、点击效果或加载动画。

### 环境与版本

- 指定的四个开发容器工具目录均为可写的独立持久挂载；API 36、Build Tools 36.0.0、命令行工具和许可证已就绪。
- `./gradlew --version`：Gradle 9.3.1，Launcher/Daemon JVM 为 OpenJDK 21.0.12.1。
- Wrapper URL、SHA-256 与计划一致，`gradlew` 权限为 755。Wrapper 使用容器预置并核对过校验和的 ZIP，没有改写缓存位置。
- 应用 Kotlin 编译器与 Compose 编译器均实际解析为 2.2.10，锁中可核对；Wrapper 版本输出的 Kotlin 2.2.21 是 Gradle 自身使用的 Kotlin。
- 直接依赖保持计划组合。应用依赖锁包含 173 个模块，以及 debug/release compile/runtime、debug AndroidTest/UnitTest classpath 和实际使用的编译/lint 工具配置。`settings-gradle.lockfile` 记录版本目录的空配置。

### 实际构建与检查

生成 Wrapper：

```bash
gradle wrapper --gradle-version 9.3.1 --distribution-type bin --gradle-distribution-sha256-sum b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06
```

首次调用在缺少 `resValues` 功能开关时失败，补齐后 Wrapper 分发地址直连校验等待较久；通过命令行 JVM 代理参数读取容器已有代理设置，成功生成，未保存代理到项目文件。

锁生成与首次完整构建的实际成功命令：

```bash
./gradlew -Dhttps.proxyHost= -Dhttp.proxyHost= :app:resolveLockedDependencies :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --write-locks
```

结果：`BUILD SUCCESSFUL in 2m 54s`。前期代理请求 Google Maven 返回 404，直连同一官方地址返回 200；重新直连解析并刷新依赖后解决，没有换仓库、改依赖版本或关闭锁。锁任务起初解析所有配置文件产生 AGP 产物歧义，解析所有元数据配置又遇到没有 BOM 的中间配置；最终只显式解析实际应用／测试 compile/runtime 依赖图，并由构建和 lint 记录实际使用的工具配置。

锁生成后不带 `--write-locks` 的复验：

```bash
./gradlew :app:resolveLockedDependencies :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

结果：首次复验成功（35s）。清理 Manifest 多余覆盖、移除本步不需要的依赖权限并补齐图标／备份迁移排除后，再执行同一命令成功（32s）。最终两变体 lint 均为 **0 errors、5 warnings**；5 条均为可升级版本提示（Gradle、Compose 编译器、两个 Coroutines 依赖和 OkHttp），保留选定固定组合，不抑制 lint。

首次构建曾提示 SDK XML 版本及无法剥离 `libandroidx.graphics.path.so` 的符号；构建仍完成，未安装 NDK 或改变工具基线。构建和 lint 成功不是功能测试通过：当前没有 JVM 或 Android 测试用例，AndroidTest 编译任务为 `NO-SOURCE`，没有运行设备测试。

最终应用依赖锁 SHA-256：`56d836a33bc96c0310d042de18152af830a614c655bc874cc3ed1500990a27dc`。在锁生成后保存校验和，最终构建后通过 `sha256sum -c`，结果为 `app/gradle.lockfile: OK`。

### APK 核对

对实际生成的三份 APK 执行 SDK `apkanalyzer manifest print`，用 XML 检查包名、SDK、回调、provider 和权限；使用 Build Tools 36.0.0 的 `aapt2 dump badging` 核对应用名称。

| 产物 | application ID | 名称／目标 |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | `io.github.chenxiex.calibrecloud.debug` | Calibre Cloud Debug |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `io.github.chenxiex.calibrecloud` | Calibre Cloud |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | `io.github.chenxiex.calibrecloud.debug.test` | instrumentation 指向 debug 包 |

三份最低 API 为 30、target API 为 36；正式 APK 不可调试。正式／debug 的 AppAuth 回调 Activity 都为 `enabled=false`，scheme 分别为对应包名加 `.disabled`，没有 OAuth 属性也可构建。初始化 provider 非导出且 authority 来自对应 application ID，没有 FileProvider，也没有 WorkManager 或 EmojiCompat initializer。

应用 APK 的唯一 `uses-permission` 是 AndroidX 的包名派生签名权限 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`；没有网络、广泛存储、唤醒、启动广播或前台服务权限。明文流量禁用，备份禁用，Android 12+ 云备份和设备迁移已排除当前普通应用数据域。

导出组件核对：主 Activity；显式禁用的 AppAuth 回调 Activity；WorkManager 的系统 JobService（受 `BIND_JOB_SERVICE` 保护）、诊断 receiver 与 ProfileInstaller receiver（受 `DUMP` 保护）。debug 另外含仅 debug 依赖带来的 Compose PreviewActivity 和测试 ComponentActivity；正式 APK 没有这两项。没有注册 worker 或提交周期任务。

`apksigner verify` 对正式 APK 返回 `DOES NOT VERIFY / Missing META-INF/MANIFEST.MF`，符合本步未签名产物预期；未使用正式签名。

| 产物 | SHA-256 |
| --- | --- |
| debug APK | `9ed5017fff48bd8bf5b8a48ec2e813c581cc320e1d875552b03c94b353be4073` |
| release unsigned APK | `f3ce1264839b3038d674c43eaa56859832c102267e78158dd8d73e41eebfb753` |
| AndroidTest APK（无用例） | `61c1fd66e5f3bcfaca338ad2c555c193bb00bc7ab710ac571b28229a7ad9f453` |

### 仓库静态检查

`git diff --check` 通过；逐文件检查新增文本也通过。生成的 Windows Wrapper 保留 CRLF（检查时按 `cr-at-eol` 识别），`.gitattributes` 固定两个 Wrapper 的平台换行约定。提交文件显式选择；`plan.md` 仍被 `.git/info/exclude` 忽略，不包含构建产物、local.properties 或调试日志。

### 用户确认与后续边界

- 展示实现、自动检查及隔离结果后，用户指示“commit吧”，确认提交步骤 01；后续功能及真机验收不因此视为通过。
- 本步未执行 ADB、安装或真机操作。SAF、OneDrive 浏览器登录、阅读器、后台恢复和墨水屏交互的对应真机验收仍未完成。
- AC01–AC09 完整功能验收保持未完成；本记录只证明已实现的工程与静态入口路径。
