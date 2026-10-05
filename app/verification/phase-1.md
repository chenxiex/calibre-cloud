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
- 显式源同步／加载和精确副本维护只有接口；已读写回只有书籍、动态栏目和固定布尔目标的意图，没有通用源上传／删除能力。契约细节见 [存储开发约束](../src/main/java/io/github/chenxiex/calibrecloud/storage/AGENTS.md)。

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

## 步骤 03：显式任务与调度信息契约

执行日期：2026-10-05。覆盖 R04–R06、R14、R16–R19、R31、R33–R34 的任务基础契约。实现和自动检查已完成；用户在审阅契约及文档归属修正后指示“提交吧”，确认验收并提交步骤 03，不开展步骤 04。

### 实现范围

新增 `tasks/api`：元数据同步、单格式复制／下载、单封面加载和固定目标布尔写回请求；防御性冻结且去重的提交集合；带依赖、资源版本比较前提及写后新鲜度的请求键；原始来源、用户提升信息、高／低优先级、同级序号及比较纯函数。必要前置请求可以继承父请求的实际来源与优先级。

任务记录表达阶段、等待原因、静态进度、处理器提供的控制能力及独立源提交证据。结果区分未提交失败、确认写入后的刷新失败、提交不明及需恢复；已提交取消保留证据，部分书籍失效可按完整身份记录原因。相关写回的顺序键独立于布尔目标；写后刷新绑定具体写回的源提交确认依赖，不与写前同步等价，也不循环等待父流程的完整成功。细节见 [任务契约说明](../src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)。

`TaskQueue` 只有显式提交／观察接口，提交结果区分新建、复用、提升和拒绝；没有内存假队列或生产接入。启动同步只定义默认关闭的设置类型与来源，没有启动钩子、worker、周期请求或新增源访问。

### 实际检查

执行两轮以下命令，均不更新依赖锁：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

首轮结果：`BUILD SUCCESSFUL in 37s`。补齐必要前置来源继承、依赖安全终点说明及逐书失效结果表达后，最终结果：`BUILD SUCCESSFUL in 33s`。

最终 JVM 测试 **25 项通过，0 failures／0 errors**：既有身份 3 项、副本读取 10 项，新增任务契约 12 项。新增覆盖来源优先级与默认关闭、必要前置来源继承、高低优先级和同级序号比较（包含 Long 最大值）、书库／UUID／格式／定位／比较版本隔离、依赖要求隔离、来源不同仍可等价、相反布尔目标不合并但共享相关写回顺序键、写后刷新屏障、集合复制／去重／顺序无关及迭代器修改拒绝、空集合／跨书库拒绝、提升保留来源并校验新序号、逐书失效身份与确认提交约束，以及失败／取消保留提交证据与正确重试边界。

debug APK 构建成功；debug lint 为 **0 errors、5 warnings**，均为既有依赖升级提示，没有新增功能告警。应用和 settings 依赖锁相对 HEAD 无变化，没有新增依赖或修改构建配置。

`git diff --check`、新增文件空白检查和相邻文档链接检查通过。审阅 `MainActivity`、Manifest 及生产源码搜索结果：主界面仍是静态入口，WorkManager initializer 仍移除，`TaskQueue` 没有实现／调用，没有周期请求或后台自动源访问。`plan.md` 仍被忽略，没有暂存或提交。

### 验收边界

本步没有 ADB 安装或真机操作，也没有实现源传输、SQLite 队列、完整调度器、去重事务、序号分配、恢复、暂停／取消执行、重试定时或通知。局部纯逻辑测试不代表这些运行能力已经通过。

用户已确认本步验收并授权提交。本步原计划不要求真机验收，仅交付基础契约与局部纯函数。AC05 的真实持久调度、后台执行、进程／设备重启恢复和对应真机验收仍未完成；已读写回的源安全提交与 Calibre 兼容验收留待第四阶段。

### 文档归属修正

用户指出任务契约不应写入 README 后，已将任务契约正文迁移至 `tasks/AGENTS.md`，删除本步新增的 `tasks/README.md`，同步修正模块导航、验证记录和临时计划中的引用。根 AGENTS.md 的文档归属约定已合并简化：开发约束和实现边界进入对应 AGENTS.md，API 详细语义进入代码注释，验证结果进入验证记录；不新增计划冲突约束。此次仅修改文档，执行 `git diff --check`、相关文档链接／空白检查及旧任务 README 引用检查，均通过；没有重复运行构建或功能测试，随后用户确认步骤 03 验收并授权提交。

## 步骤 04：本地 SAF 目录授权入口

执行日期：2026-10-05。覆盖 R04、R07、R20、R31–R35 的本地平台授权路径。实现、自动检查与 ADB 真机功能验证已完成；用户于 2026-10-05 确认全部手动步骤通过，完成墨水屏实体显示／手动操作验收，按计划提交步骤 04；未开展步骤 05。

### 实现范围

- Activity Result 发起 `ACTION_OPEN_DOCUMENT_TREE`，请求读写、持久和前缀授权；URI 与真实结果 flags 交给 `storage/local`。只接受已识别的系统本地／SD 卡 tree provider，未知和云端提供方明确拒绝。Manifest 只新增本地 provider authority 的包可见性查询，没有存储或网络权限。
- 持久权限只取实际返回的读写 flags；缺持久／读取授权拒绝保存。私有 SharedPreferences 保存位置，恢复时核对 OS 持久授权，区别未选择、读写、只读、失效与不支持提供方。新配置保存成功后才释放旧授权；取消与失败保持原选择，失败清理新授权，不误释放同 URI 的授权。
- 代码复核发现 Activity 自有协调器可能在保存期间重建后留下旧 UI 快照，实施中改为保留 ViewModel，并串行执行授权操作与结果交付，忙时不重复恢复；没有依赖 Activity 生命周期取消已经提交的授权操作。为这条路径增加共享 ViewModelStore、独立 Main／I/O scheduler 的必要测试。
- 配置／授权 I/O 在后台执行；入口用黑白文字和边框、无 ripple 的控件与显式两页切换，无滚动或应用过渡／加载动画。页面明确“目录已授权，书库待验证”，不读取、枚举、复制或写入源文件，不导入元数据，不建立 LibraryId，不发起队列／同步。
- 提供方规则、权限 flags 与私有配置细节见 [本地授权开发约束](../src/main/java/io/github/chenxiex/calibrecloud/storage/local/AGENTS.md)，使用入口见 [模块 README](../README.md#本地目录授权)。授权 flags 不是源目录仍存在或 R15 安全提交能力的证据，真实文件能力探测留待后续后端实现。

### 自动检查

最终生产代码执行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

结果：`BUILD SUCCESSFUL in 35s`。随后补齐 3 项 ViewModel 测试，仅执行受影响检查：

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug
```

结果：`BUILD SUCCESSFUL in 30s`。最终 JVM **40 项通过，0 failures／0 errors**：既有 25 项、本地授权 12 项、ViewModel 3 项。新增覆盖取消、实际 flags 转交、持久授权缺失／不可读／失败、只读恢复、撤销和未知提供方、配置保存失败清理、保存与释放顺序、同 URI 重试、释放异常和后台 dispatcher；ViewModel 覆盖重建复用及 pending 结果、忙时跳过恢复、多个选择按序交付及所有结果结束前保持 busy。适配器只有授权元数据与配置 API，没有源文件访问。

最终 debug／release lint 均为 **0 errors、5 warnings**，全部是既有依赖升级提示。本步 `commit()` 必须返回持久保存结果且在后台执行，局部注明理由并抑制 `ApplySharedPref`／`UseKtx` 建议；不能用无提交结果的 `apply()` 替代。应用与 settings 依赖锁逐字节对照 HEAD 无变化，没有新增依赖或修改版本。

执行 `apkanalyzer manifest print` 核对最终 debug、release unsigned 和 AndroidTest APK：独立包标识与 instrumentation 目标正确；FileProvider 的 authority 分别派生自对应 application ID，非导出；AppAuth 回调仍禁用；备份仍禁用且普通配置所在 sharedpref 域已排除备份和迁移。没有新增网络、广泛存储或后台权限。

| 最终产物 | application ID | SHA-256 |
| --- | --- | --- |
| debug | `io.github.chenxiex.calibrecloud.debug` | `03e89ea24c0d0ac5c4216916a4c93acfb32846bd639bf91c2d410c1457d3ad2a` |
| release unsigned | `io.github.chenxiex.calibrecloud` | `75c37681dabf653aead97d1be06c98af75b6b0d1226330f23b5121bd364261a1` |
| AndroidTest | `io.github.chenxiex.calibrecloud.debug.test` | `db9726cead1d001723b28da61d367c42d7a6ca74b15ccb0c4f5ef538dcd6f0c3` |

### 真机操作与结果

设备沿用步骤 02 已核对的 PA6，Android 14／API 34。`adb devices -l` 显示单一已授权连接；安装前核对实际 APK application ID，并查询限定包名前缀，确认 debug／测试包不存在。只安装独立 debug 和测试包，均返回 `Success`。迭代中仅替换本次自己安装的 debug／测试包，保留本次测试配置，没有接触正式包或其数据。

限定检查 `com.android.externalstorage` 包：本地 authority 为 `com.android.externalstorage.documents`，包来自系统 `priv-app`，flags 含 `SYSTEM`，符合首轮规则。将 `assets/calibre-sample/` 的全部 5 个文件推送到 Download 下新建的专用步骤 04 测试目录，操作前逐文件 SHA-256 与仓库样本一致；未选择真实书库。

通过 ADB 点击真实系统文件选择器中的专用测试目录、“使用此文件夹”及“允许”，应用显示“目录已授权（读写），书库待验证。尚未导入书籍。”。之后在最终 ViewModel 版本完成：

| 路径 | 实际结果 |
| --- | --- |
| 重新打开选择器后取消 | 恢复原读写授权；私有配置内容逐字节不变 |
| `am force-stop` 后重新启动 | 恢复原持久目录授权，不再次要求选择，不导入或同步 |
| 显式下一页／上一页 | OneDrive 占位页与本地页切换成功；应用节点没有 scrollable 标记 |
| Activity 重建 | 以下 Android 测试核对配置、实际持久授权和页面均保持 |
| 撤销本次 debug 持久授权后重建 | 页面显示“目录授权已失效，请重新选择。”；保留原配置位置 |

系统选择器返回键可能先返回父目录，首次脚本未完成取消就检查应用状态，未通过该脚本断言；调整为确认退出选择器后再检查，最终取消路径通过。UIAutomator 在个别切换时返回 `null root node`，最终重新读取应用页面及独立 instrumentation 检查均成功；不将中间未取得页面的调用记为通过。

执行本步专项 Android 测试（必须先经真实选择器授权测试目录，测试会撤销该授权）：

```bash
adb -s <已核对设备> shell am instrument -w -r -e class io.github.chenxiex.calibrecloud.storage.local.LocalDirectoryAuthorizationDeviceTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

最终结果：**OK (1 test)**，用例状态码 0，runner 时间 3.378s。验证系统本地 tree URI 识别；云端、file、非 tree 和附加 document 路径拒绝；缺 persistable flag 拒绝；实际读写持久授权及私有配置在 Activity 重建后保持；释放真实持久授权后显示重新授权入口。runner 时间不是产品响应指标。JVM 可控 scheduler 测试另覆盖操作仍 pending 时重建的交付，不把已完成后的设备重建测试冒称覆盖全部并发时机。

全部授权、取消、重启、重建、撤销操作后，再逐文件检查专用书库的 5 个 SHA-256，均与原样本一致。应用没有源文件读写；校验由 ADB 对测试副本执行。未采集全设备日志、未记录用户 URI、凭据或真实书库内容。检查应用入口截图：文字、按钮与失效提示可见；物理墨水显示的残影、手动点击可用性仍须用户确认。

记录后依次卸载 debug 测试包和应用包，均返回 `Success`；查询限定包名前缀，确认两包均不存在。仅清理本次新建的专用测试书库副本和 UIAutomator 临时文件，不改动仓库样本、真实书库或正式应用。

### 静态检查与未完成验收

`git diff --check`、本步文档链接和新文本空白检查通过。`plan.md` 仍被忽略，不暂存、不提交；步骤 04 用户验收已通过，按计划提交本步。

- 用户共同验收及墨水屏实体显示／手动操作：**通过**。用户确认全部手动步骤通过；验收后 ADB 复核专用副本的 5 个文件未改变，完成卸载清理。
- SD 卡目录与云端提供方真实选择器场景：**未执行**。`sm list-volumes public` 未列出已挂载 public volume；未为本步新增硬件或安装云端提供方。自动拒绝测试不替代真实云端入口操作，条件场景后续补验，不作为本步提交门槛。
- 真实本地文件读写能力、目录移除检查、Calibre 书库验证、元数据导入、队列、书籍复制／下载、阅读器及 AC03 完整验收：**后续阶段**。本步授权成功不代表这些功能通过。

### 用户手动验收（2026-10-05）

用户要求安装 debug 包并进行墨水屏实体显示／手动操作验收。重新确认 PA6 的 ADB 连接，核对 APK 为可调试的独立包 `io.github.chenxiex.calibrecloud.debug`，SHA-256 与上表最终 debug 产物一致。限定包列表确认 debug 包不存在后，执行不带替换参数的 `adb install`，返回 `Success`；执行 `am start` 打开主 Activity。此次仅安装应用包，没有安装 AndroidTest 包。

在设备 Download 下新建专用 `calibre-cloud-step04-manual-20261005` 目录，推送仓库样本的 5 个文件；逐文件 SHA-256 与仓库样本一致。此目录仅用于本次手动验收，用户选择该副本，不选择真实书库。

手动检查如下；用户完成后明确反馈“全部步骤通过”，以下 6 项均按用户实际操作结果记为通过：

1. 首页确认“未选择目录”，文字和按钮无截断／重叠，灰度下清晰，记录残影是否影响阅读。
2. 点击“选择／重新授权目录”，取消系统选择器并返回应用，确认仍未选择；系统返回键可能先返回父目录，需要完全退出选择器。
3. 再打开选择器，进入设备本地存储的 `Download/calibre-cloud-step04-manual-20261005`，点击“使用此文件夹”并允许，确认显示“目录已授权（读写），书库待验证。尚未导入书籍。”。
4. 授权后重选并取消，确认原授权状态保留；观察按钮点击和页面变化是否可辨认。
5. 点击下一页查看 OneDrive 占位页，再点击上一页返回本地页；确认无需滑动，按钮与文字可读，无应用过渡、ripple 或加载旋转动画。系统选择器动画不纳入应用保证。
6. 通过系统应用信息对 Calibre Cloud Debug 执行强行停止后重新打开，确认仍为已授权／书库待验证。

用户反馈后，重新通过 ADB 逐文件核对手动测试副本的 SHA-256，5 个文件均与仓库样本一致。执行 `adb uninstall io.github.chenxiex.calibrecloud.debug` 返回 `Success`；限定包查询确认 debug／测试包均不存在。仅删除本次新建的手动测试副本，未接触正式应用或真实书库。用户手动验收通过，按 `plan.md` 的确认后提交流程提交步骤 04；SD 卡／云端条件场景仍未执行，步骤 05 未开始。


## 步骤 05：按变体生成 OneDrive OAuth 配置（2026-10-05）

对应 R08、R31、R33–R35。本步读取约定的三项构建属性，生成变体运行配置及精确回调接收范围；入口区分缺配置与配置有效但尚未登录。完整回调地址检查允许协议返回的 query，拒绝不匹配的 scheme、authority、path 和 fragment；地址检查不替代后续 state／待处理事务验证。本步不启动浏览器、不访问 Graph、不兑换或存储令牌。

### 构建与必要修正

初次执行 JVM 测试、两变体构建和 lint 时，启用 BuildConfig 新增 Java 编译路径，严格依赖锁拒绝缺少锁状态的 `androidJdkImage` 配置。按锁维护约定执行：

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --write-locks
```

结果 `BUILD SUCCESSFUL`。锁文件只在 `empty` 行新增 `androidJdkImage`，依赖和版本没有变化。

合并产物复核发现仅替换 intent-filter 未清除依赖的另一条宽泛 scheme filter，已改为整体替换 receiver。带 host／无 host 使用两个字面 Manifest 模板；无 host 必须省略 host/path 属性，空属性不能视为缺省。模板选择、placeholders 与 BuildConfig 共用同一 URI 解析结果。

修正后执行不更新锁的验证：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

结果 `BUILD SUCCESSFUL`（2m 20s）。JUnit XML 汇总 **42 项测试，0 failures、0 errors、0 skipped**，其中新增的 2 项测试覆盖完整回调地址拒绝与无 host 路径检查。AndroidTest APK 构建成功不代表设备测试通过。本步两个 lint 报告均成功生成；工具提示 SDK XML schema 版本差异，未影响任务结果。

### 用户追加的构建资源限制

用户反馈构建占满 CPU 后，在根 `gradle.properties` 设置 worker 上限 2、关闭项目并行、构建 JVM `ActiveProcessorCount=2`，堆上限保持 2 GiB。终止本次旧矩阵进程后按新设置重新运行，未停止其他项目或 IDE 的 daemon；进程参数已确认包含新的 JVM 限制。矩阵按顺序执行 fixture，不同时启动多组构建。配置解释和进一步降到 1 的临时命令见 [工程构建说明](../README.md#构建)。这些设置不是整个进程树的硬 CPU 百分比或总内存配额。

### 构建矩阵与验收状态

执行 `python3 scripts/verify-oauth-config.py`，结果 **13/13 通过**。每组在被忽略的 `.oauth-verification/` 内使用独立工程副本，均继承当次资源限制（2 个 worker／JVM 处理器），并以固定锁文件构建；原 `local.properties` 的逐字节不变检查通过。完整 JSON 汇总和脱敏 Gradle 日志留在该临时目录，不提交。

| Fixture | 实际结果 |
| --- | --- |
| `missing` | 两变体构建成功；运行值为空、接收器禁用且禁用 scheme 独立 |
| `partial-client`、`partial-callback` | 同上；不打包部分配置 |
| `host-a`、`host-b` | 两组独立回调各自正确生成 |
| `no-host` | 两变体构建成功；Manifest 无 host/path 属性，运行配置保留完整单斜杠 URI |
| `same-scheme-hosts`、`same-scheme-paths` | 接收范围可隔离；生成精确 host/path，无遗留宽泛 filter |
| `safe-client-string` | 引号和反斜线安全生成，编译后配置与输入一致 |
| `invalid-scheme`、`credentials` | 完整配置构建失败，指出相应属性 |
| `overlap`、`no-host-overlap` | 完整配置构建失败，同时指出两个回调属性与范围重叠 |

所有可构建 fixture 的 debug/release 共 18 个 APK 均由 SDK `apkanalyzer manifest print` 核对 application ID、接收器启用状态、唯一 filter、scheme 与适用的 host/path；同时核对 generated BuildConfig 的 client ID、对应完整回调和 merged Manifest。测试注册标识仅为虚构构建输入，未验证微软注册有效性或真实登录。

资源配置使用 `/tmp` 中的临时 Gradle init 探针核对运行时值，再执行完整工程命令，不并行运行矩阵与工程复验：

```bash
./gradlew -I /tmp/calibre-step05-resource-check.gradle verifyBuildResourceLimits :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

探针断言实际 worker 上限为 2、项目并行关闭、`Runtime.availableProcessors()` 为 2，并确认 JVM 参数中存在 `-XX:ActiveProcessorCount=2`。结果 `BUILD SUCCESSFUL`（1m 3s；9 executed、129 up-to-date），复用同一输入的 42 项 JVM 测试结果。两变体 lint 均为 **0 errors、5 warnings**，警告均为固定工具／依赖的版本更新提示；本步不调整既有版本。临时探针只用于核对本次运行参数，不引入生产构建任务。

现有开发者配置下的实际 APK 另核对 API 30、debug `.debug` 与正式包隔离、可调试状态、回调启用状态与唯一 filter、备份关闭、`${applicationId}.books` authority。网络／后台平台权限仍未开放，保留 AndroidX 生成的应用私有 signature 动态接收权限；首次产物探针把此原有权限也当作禁止项，断言失败，修正检查范围后两变体均通过。本地授权入口与配置保存代码未改动，缺 OAuth 配置不关闭本地入口。

| 最终产物 | SHA-256 |
| --- | --- |
| debug APK | `f7c775fd5eb7d7f2f07594e2a4ca41c0df330f417ac273aa66c105b86df6e55a` |
| release 未签名 APK | `9bf389d2997517464d02fc9f89da51eda7f612a736195bd3f0e2224ac7a9ea9c` |

`git diff --check`、Python 脚本编译检查和改动文档的本地链接检查通过；`plan.md`、`local.properties`、矩阵副本及产物仍被忽略，未暂存或提交。

- 用户共同审阅构建矩阵、配置错误和入口说明：**已确认**。用户审阅并调整资源配置及文档后，明确要求提交步骤 05（2026-10-05）。
- 本步不安装应用或操作测试书库；未执行真实登录或真机回调验证。这些与令牌安全存储属于步骤 06。
- 完整 AC09、本地／OneDrive 源访问、持久队列、同步、下载与阅读器仍按后续阶段验收，不以本步构建结果视为通过。


### 资源上限调整为 4（2026-10-05）

用户要求将 worker 上限与 JVM 可用处理器数提高到 4。根 `gradle.properties` 已设置 `org.gradle.workers.max=4` 和 `-XX:ActiveProcessorCount=4`；`org.gradle.parallel=false` 与 2 GiB 堆上限保留。此为构建资源参数调整，关联 R34 的工程验证，不改变产品行为或步骤 05 验收门槛。

执行临时 init 探针及 `:app:assembleDebug :app:assembleRelease`：

```bash
./gradlew -I /tmp/calibre-resource-check-4.gradle verifyBuildResourceLimits :app:assembleDebug :app:assembleRelease
```

探针断言并实际输出 `workers=4, parallel=false, JVM processors=4`，同时核对 JVM 参数中包含 `-XX:ActiveProcessorCount=4`。构建结果 `BUILD SUCCESSFUL`（17s；4 executed、86 up-to-date）；`git diff --check` 通过。上述 13 组 OAuth 矩阵和完整测试／lint 记录使用先前的 2 上限，不将其改记为在 4 上限下重跑；此次只复验资源配置和两变体构建。

`org.gradle.parallel` 是项目间任务并行开关；可设置为 `true` 并由 `org.gradle.workers.max=4` 约束 worker 并发，但这不是整个进程树的线程数上限。当前单 `:app` 模块继续关闭项目并行，不影响模块内部 worker 并发。配置说明已同步到工程 README 与 Gradle 开发约束。

用户随后要求将项目并行选项恢复默认，已删除根 `gradle.properties` 中显式的 `org.gradle.parallel=false`，保留 worker 与 JVM 处理器上限 4，并同步当前配置说明。执行 `./gradlew -I /tmp/calibre-resource-check-4.gradle verifyBuildResourceLimits`，实际输出仍为 `workers=4, parallel=false, JVM processors=4`，`BUILD SUCCESSFUL`（1s）；`git diff --check` 通过。项目并行默认仍为关闭，此次没有改变构建行为，不重复功能测试或构建矩阵。

按用户要求精简工程 README 的构建部分：保留环境参数、常用检查命令、默认资源参数与临时覆盖命令，删除 parallel 说明和扩展解释。只调整文档，未改变构建配置或产品行为；README 本地链接检查、parallel 文本移除检查和 `git diff --check` 通过，未重复构建或功能测试。

按用户要求拆分 OneDrive 文档：工程 README 保留配置属性、注册链接、缺配置／错误处理、当前登录能力和验证命令；构建生成、回调接收范围、完整地址校验、协议参数、隐私及矩阵隔离约束移入授权模块 `AGENTS.md`。只调整文档，R08 等实现与验收范围不变；改动文档的本地链接检查和 `git diff --check` 通过，未重复构建或功能测试。

## 步骤 06：个人 OneDrive 授权与阶段联验（2026-10-05）

对应 R04、R08、R20、R31–R35。实现系统浏览器个人账号授权、完整回调／state／期限门禁、AppAuth 授权码兑换和显式刷新、Keystore AES-GCM 私有状态存储，以及静态登录状态与取消／重试入口。授权请求使用 `consumers`、授权码及 S256 PKCE，范围为 `openid`、`offline_access`、`https://graph.microsoft.com/Files.ReadWrite`；没有 Graph 文件请求、书库选择或源文件访问。实现细节见 [授权模块约束](../src/main/java/io/github/chenxiex/calibrecloud/auth/AGENTS.md)。

### 自动构建与测试

执行不更新依赖锁的命令：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

最终结果 `BUILD SUCCESSFUL`（1m 26s），**48 项 JVM 测试，0 failures、0 errors、0 skipped**，包含新增 6 项回调门禁测试。debug、未签名 release 和 AndroidTest APK 均生成；两变体 lint 均为 0 errors、9 warnings，另有 1 项 informational 提示。5 项 warnings 是既有固定工具／依赖更新提示，4 项是新增 URI 调用的 KTX 风格建议；informational 为整型 Compose 状态装箱建议，不影响构建。本次未修改依赖版本或锁文件。实现期间新增刷新测试使用了 AppAuth 不存在的常量，AndroidTest 编译失败；改为协议字面值 `refresh_token` 后最终命令通过，不将失败命令记为通过。

最终 APK SHA-256：

| 产物 | SHA-256 |
| --- | --- |
| debug | `046a89a6055ffc28db0b8a493398f60f595726c5bf1299d5a3598a462b7659a4` |
| release 未签名 | `763340f06c2f286b1cd3ae1e163e7fa5cb20d5d616f3480a7709d838eab5604c` |

使用 SDK `apkanalyzer manifest print` 核对三个实际 APK 的 application ID：正式包 `io.github.chenxiex.calibrecloud`，debug 包 `.debug`，测试包 `.debug.test`。核对 debug 可调试、release 不可调试，书籍 provider authority 隔离且不导出、备份关闭。网络权限仅新增 OAuth 必需的 INTERNET，未开放后台／启动权限。原 AppAuth 导出 receiver 已移除，变体回调仅由应用 receiver 接收，完整地址和事务仍由协调器校验。

### 受影响配置路径回归

使用 `scripts/verify-oauth-config.py` 的原有 fixture 和检查函数，仅选择本次受影响的 `missing`、`host-a`、`no-host`，依序执行两变体构建、generated BuildConfig、merged Manifest 和 APK 检查：**3/3 通过**。原配置逐字节不变检查通过；确认新 receiver 没有遗留依赖的宽泛 receiver。其余 10 组配置规则未改动，不将步骤 05 的 13/13 记录改记为本次全部重跑。此次 `.oauth-verification/summary.json` 保存的是这三组回归结果。

实际执行入口：

```python
import importlib.util
spec = importlib.util.spec_from_file_location("oauth", "scripts/verify-oauth-config.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
selected = [item for item in module.fixtures() if item[0] in {"missing", "host-a", "no-host"}]
module.fixtures = lambda: selected
raise SystemExit(module.main())
```

### 设备自动测试与入口探测

设备：PA6，Android 14 / API 34，使用 ADB 已连接设备；安装前从实际 APK 核对独立 debug 和测试 ID。没有安装正式包，没有读取或修改真实／示例源书库。授权平台不访问源文件，此次无需书库数据 fixture；加密测试使用独立测试 namespace，协议测试使用注入的浏览器／令牌结果。

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.auth.EncryptedAuthStateStoreTest,io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationTest,io.github.chenxiex.calibrecloud.files.BookFileProviderTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

结果 **OK (21 tests)**（2.304s）：5 项真实 Keystore／AES-GCM／密文恢复与 provider 拒绝测试、11 项授权协调器测试、5 项原有书籍 provider 隔离测试。涵盖有效／错误／重复回调、取消、服务器／网络／invalid_grant 分类、无浏览器后恢复、PKCE pending 恢复、兑换前持久消费和中断恢复、凭据损坏只清除授权状态。模拟令牌结果不证明真实微软登录成功。

通过 ADB 和 UI hierarchy 探测实际入口：本地未选择状态及目录选择入口可见；第二页可启动设备浏览器 `com.Hanovn.HanovnBrowser/.DefaultBrowserActivity`。未操作账号或 consent 页面。浏览器返回后仍有静态登录状态与显式取消入口；强制停止 debug 应用并启动后，在第二页恢复了加密保存的 pending 请求（连续两次进程重启），取消后出现取消说明及重新登录入口。使用实际配置的回调地址附加虚构 code/state，系统解析到 debug receiver；没有待处理请求时拒绝回调并显示可重试提示，没有兑换。UI 探针首次使用了错误的相对 Activity 类名，修正为 namespace 下完整类名；另一次 hierarchy 暂时返回 null root，重新抓取后确认实际恢复状态，不将这两次探针失败当作产品验收通过。

抽查 `OneDriveAuth`／`AppAuth` 日志：39 条应用授权事件仅含随机操作 ID、固定阶段或错误分类，没有测试 access/refresh token、授权码、PKCE verifier 或完整 URL。密文在 noBackupFilesDir，设备测试确认真实密文路径无法由书籍 provider 提供；备份／迁移禁用和排除规则保持有效。

### 自动验证结束时的未完成项

以下为用户手工验收前的状态，后续结论以文末用户验收记录为准。

- 真实个人测试账号的登录成功、Microsoft consent、实际授权码兑换／刷新，以及已授权令牌的进程重启恢复；本次只证明浏览器启动与 pending 请求恢复。
- 真实断网／无兼容浏览器条件下的恢复，Activity 重建后的真实浏览器往返，以及用户在目标墨水屏上的共同验收。注入故障测试不能替代这些验收。
- 步骤 04 的 SD 卡／云端提供方条件补验继续保留原状态；本步未重复本地授权外部选择和授权撤销操作。
- 本步用户共同验收与提交未完成，第一阶段尚未关闭；持久队列、同步、下载、书库 UI、阅读器、写回及 AC01–AC09 完整验收仍属于后续阶段。

补充联验：安装已核对包标识的 `missing` fixture debug APK（覆盖的仍仅为独立 debug 测试沙箱），确认第一页本地目录选择入口可用，第二页显示未配置指引且不提供登录按钮。没有执行系统目录选择或访问任何源书库。此项证明缺 OAuth 配置不会关闭本地入口，不替代步骤 04 的真实持久授权验收。

记录结果后执行 `adb uninstall io.github.chenxiex.calibrecloud.debug.test` 和 `adb uninstall io.github.chenxiex.calibrecloud.debug`，均返回 `Success`；随后 `pm list packages` 确认两个测试包均不存在。临时设备 UI hierarchy 文件已删除。脱敏测试输出、日志及探针留在 `/tmp`，配置 fixture 留在被忽略的 `.oauth-verification/`，未加入版本控制。改动文档的本地链接检查、Python 源码编译检查与 `git diff --check` 通过；未暂存或提交，未改变用户的 local.properties。

### 用户手工验收准备（2026-10-05）

用户要求重新安装 debug 包并提供真机操作指引。执行 `./gradlew :app:assembleDebug`，`BUILD SUCCESSFUL`（1s，39 项 up-to-date）；APK SHA-256 与上文最终 debug 产物一致。重新通过 apkanalyzer 核对 `.debug` application ID、可调试状态、独立 `.debug.books` provider 和启用的 OAuth receiver，生成配置为完整启用；这不证明微软注册匹配或账号授权成功。

通过 ADB 在当前 PA6 设备执行 `install -r`，返回 `Success`，并启动 `io.github.chenxiex.calibrecloud.debug/io.github.chenxiex.calibrecloud.ui.MainActivity`。只安装应用 debug APK，未安装正式包或 AndroidTest 包。当前按用户要求保留 debug 应用用于手工验收；账号登录、恢复及故障操作待用户完成并回报，尚未更新为通过。验收记录完成后再卸载测试应用。


### 用户真机验收结论（2026-10-05）

用户反馈上述操作指引的全部六项结果符合预期，并明确确认：“无浏览器场景未测试；暂不测试，不阻塞提交。”本步共同验收通过，按用户确认提交步骤 06；无浏览器场景保留为未执行的非阻塞补验项，不将注入故障测试记为该场景真机通过。

| 用户操作 | 用户确认结果 |
| --- | --- |
| 个人微软测试账号浏览器登录、授权并返回 debug 应用 | 显示“个人 OneDrive 已授权” |
| 显式刷新授权 | 仍显示已授权 |
| 强行停止后重新启动、进入第二页 | 恢复已授权状态，无需重新登录 |
| 浏览器返回后显式取消 | 显示取消说明，可以重新登录 |
| 断网刷新、恢复网络后重试 | 网络错误与重试结果符合预期 |
| 墨水屏文字／按钮／分页、滚动和加载动画观察 | 符合预期 |

上述真实账号结果来自用户操作与确认；未采集账号、密码、令牌或完整回调 URL。Activity 重建后的真实浏览器往返未收到单独操作记录，不补写为实测通过；现有进程重启验收与自动状态恢复测试保持各自证据范围。阶段结论遵循用户共同验收；源后端、持久任务、同步、下载、阅读器和已读写回仍未实现，AC01–AC09 完整功能验收不因此关闭。步骤 04 条件补验保持原记录。

保存用户验收结果后，ADB 检查确认设备上只有应用 debug 测试包；执行 `adb uninstall io.github.chenxiex.calibrecloud.debug` 返回 `Success`，再次检查确认 debug 与 AndroidTest 包均不存在。此次没有安装或卸载正式应用。用户共同验收后仅更新验证记录与临时计划，不改生产代码，沿用本步已通过的构建、测试和 lint；执行文档本地链接检查、提交范围检查和 `git diff --check`。
