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

## 步骤 02：身份与完整副本读取边界

执行日期：2026-10-05。覆盖 R03–R06、R07 的应用副本边界、R28 的 provider 范围、R31–R33；AC01／AC02 仅覆盖契约与局部读取路径。实现、自动检查和步骤 02 平台测试运行已完成；用户在审阅运行时验收结果后指示“提交”，确认提交本步。

### 实现范围

- 源位置与内部书库身份分离；书籍键包含书库、源数字 ID 和 UUID，格式规范化后进入副本键。后端定位、文件版本、自定义栏目身份及安全逻辑相对路径分别建模。
- 最小完整下载记录包含身份、内部文件代次、标题、可空大小、已保存版本与三种源状态。结构化错误不携带异常全文或凭据 URL。
- `PrivateCopyReader` 仅查询完整记录并在注入的 I/O dispatcher 打开应用文件；缺失不下载、不访问源、不等待队列。`PrivateBookFiles` 只读取 `filesDir/books/<LibraryId>/<fileGeneration>.book`，拒绝链接、目录、空文件及已知长度不符；调用方关闭句柄，取消交付时由读取器关闭未交出的句柄。
- FileProvider 非导出，authority 按 application ID 派生，XML 只开放 `filesDir/books/`。当前没有外部 Intent 或 URI 授权流程；第三阶段授予临时只读权限并实现 MIME／友好显示名。
- 显式源同步／加载和精确副本维护只有接口；已读写回只有书籍、动态栏目和固定布尔目标的意图，没有通用源上传／删除能力。契约细节见 [存储说明](../src/main/java/io/github/chenxiex/calibrecloud/storage/README.md)。

### 实际检查与锁定补齐

首轮运行计划中的 `:app:testDebugUnitTest :app:assembleDebugAndroidTest :app:lintDebug`，并附加受 Manifest 影响的 release 构建／lint。因步骤 01 没有实际 JVM 测试，`debugUnitTestAnnotationProcessorClasspath` 尚无锁状态，严格锁定拒绝解析。随后执行：

```bash
./gradlew :app:resolveLockedDependencies :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --write-locks
```

结果：`BUILD SUCCESSFUL in 40s`。仅增加实际 JVM 测试空 annotation processor 配置，以及 JVM／AndroidTest Compose 编译器配置的锁定归属；没有新增依赖模块、改变版本或关闭严格锁定。清理格式构造与 fixture 可空文件父目录的编译告警后，不带 `--write-locks` 复验成功（34s）。

复核句柄生命周期时，先添加 `cancellationBeforeHandleDeliveryClosesTheUndeliveredCopy`，运行 `./gradlew :app:testDebugUnitTest` 稳定复现取消交付造成未关闭句柄的问题（13 项测试，1 项失败）。修正读取器的取消清理后执行最终复验：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease :app:lintRelease
```

最终结果：`BUILD SUCCESSFUL in 34s`；13 项 JVM 测试全部通过（身份校验 3 项、副本读取 10 项），0 failures／0 errors。覆盖完整副本读取与关闭、I/O dispatcher、缺失不调用句柄工厂、暂存不可见、跨书库／数字 ID 重用／格式隔离、查询返回错误身份拒绝、空文件／长度不符／目录拒绝、文件和父目录链接拒绝、权限／I/O 错误映射、源缺失仍打开旧副本，以及取消交付时关闭句柄。独立源 fixture 的访问计数在副本读取后仍为 0，源内容保持不变；普通读取器没有源／网络依赖。

debug／release lint 均为 **0 errors、5 warnings**，仍全部是既有依赖升级提示，没有新增功能告警。保存锁校验和后，在最终不更新锁的构建后执行 `sha256sum -c`：应用与 settings 锁均为 `OK`；应用锁最终 SHA-256 为 `eb6f6d9ba16d9fe1719b6872bc94eb96435933a637724bc4768269ffd24c7bf5`。

### APK 与平台验证

对最终 debug／release／AndroidTest APK 执行 `apkanalyzer manifest print`：

| 产物 | 包标识 | 书籍 provider authority |
| --- | --- | --- |
| debug | `io.github.chenxiex.calibrecloud.debug` | `io.github.chenxiex.calibrecloud.debug.books` |
| release unsigned | `io.github.chenxiex.calibrecloud` | `io.github.chenxiex.calibrecloud.books` |
| AndroidTest | `io.github.chenxiex.calibrecloud.debug.test` | 使用目标 debug 应用的 provider |

两个应用 APK 的 FileProvider 均为 `exported=false`、`grantUriPermissions=true`。执行 `apkanalyzer resources xml --file res/xml/book_paths.xml app/build/outputs/apk/debug/app-debug.apk`，确认打包 XML 只有 `files-path name="books" path="books/"`，没有父目录或其它路径配置。

已编译 5 项 Android provider 测试：完整副本 URI／字节读取；父目录、数据库、暂存、凭据、封面、普通 cache 和相似前缀目录拒绝；URI／文件路径穿越拒绝；符号链接逃逸拒绝；debug authority 和非导出配置核对。

首次开发检查时 `adb devices -l` 列表为空，仅完成平台测试编译。用户随后连接生产设备并授权步骤 02 运行时验收，于 2026-10-05 补充执行以下真机验证。

设备属性：manufacturer `QUALCOMM`、model `PA6`，Android 14／API 34；设备型号采用实际 ADB 属性，不根据规格中的目标名称推断。安装前核对实际 APK 为独立 debug／测试包，instrumentation 目标为 `io.github.chenxiex.calibrecloud.debug`；通过限定包名前缀的 `pm list packages` 确认两个待安装包均不存在。初次预检误将不存在包时 `pm path` 的退出码 1 视为命令失败，未执行安装；随后使用包列表预检，未覆盖任何已有安装或数据。

本次 APK SHA-256：

| 产物 | SHA-256 |
| --- | --- |
| debug | `3764a74b3a2284e91692bb7608f4071536db751f9115b4ba51ee73f0ec3a0d38` |
| AndroidTest | `dc7f57a1ddb1ce760177ae84ea3b968add03dfd47e6958674469c6e9613f567f` |

在明确选定的已连接设备上执行 `adb install`，不使用替换安装选项。两个包均返回 `Success`。执行的 instrumentation 参数为：

```bash
adb -s <已核对设备> shell am instrument -w -r -e class io.github.chenxiex.calibrecloud.files.BookFileProviderTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

结果：**OK (5 tests)**，5 个用例状态码均为 0，runner 报告测试执行时间 0.118s。完整副本读取、私有目录拒绝、路径穿越拒绝、符号链接逃逸拒绝及 debug provider 隔离均在实际 Android 系统上通过；该时间仅为测试运行时间，不作为产品性能指标。

生产数据保护范围：仅在本次新安装 debug 应用的私有沙箱内创建随机唯一名称的合成文本 fixture，包括测试数据库／暂存／凭据路径中的假文件；不选择或读取真实书库，不读取正式应用数据，不访问共享存储，不获取存储授权，不更改设备设置，也不采集全设备日志。未使用 Calibre 样本或真实凭据，本次测试不代表真实后端或外部阅读器验收。

测试后依次执行 `adb uninstall io.github.chenxiex.calibrecloud.debug.test` 和 `adb uninstall io.github.chenxiex.calibrecloud.debug`，均返回 `Success`；再次查询限定包名前缀的包列表，确认 debug／测试包均不存在，测试沙箱随卸载移除。没有安装或卸载正式包。外部阅读器打开、显示名和临时授权验收仍属第三阶段。

### 静态检查与验收边界

`git diff --check` 及新增文本空白检查通过；相邻文档链接目标存在。`plan.md` 仍被 `.git/info/exclude` 忽略，没有暂存或提交。本步没有源复制、Graph 请求、持久队列、持久下载清单、真实书库复用／替换识别或源写回；步骤 02 的 5 项平台测试运行已通过；用户已确认提交步骤 02；AC01／AC02 完整功能验收仍待后续阶段实现，本次不开展步骤 03。
