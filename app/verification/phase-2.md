# 第二阶段验证记录

范围以 [spec.md](../../spec.md) 第二阶段“后端与导入”为准。本记录保存实际执行结果；自动检查通过不等于用户共同验收通过，尚未实现的后端、导入、传输、清理和后台路径不能提前记为通过。

## 步骤 01：持久应用状态与最小下载清单（2026-10-06）

对应 R03–R06、R12、R32–R34。已实现原生 SQLite schema v1、候选位置／选择代号、验证后身份绑定、当前配置、本地授权配置兼容迁移，以及按完整书籍身份与格式隔离的持久完整副本查询和分页。应用依赖容器共享状态库、本地授权组件和 `PrivateCopyReader`；OneDrive 凭据仍由原加密存储管理。Schema 与事务语义见 [状态库约束](../src/main/java/io/github/chenxiex/calibrecloud/state/AGENTS.md) 及相邻 KDoc。

选择新位置生成新代号并解除当前身份引用，旧绑定及清单保留；重开数据库可恢复当前候选或已绑定身份。重新选择旧位置须经后续导入核验后复用历史绑定；本步没有生产身份验证、下载、任务调度或清单界面，完整记录仅由私有测试 fixture 准备。位置配置不等于 Calibre 导入成功。

### 构建、JVM 与静态检查

使用项目 Wrapper，不更新依赖锁：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

实现初次检查 `BUILD SUCCESSFUL`（1m 2s），包含 48 项既有 JVM 测试。新增平台测试与历史绑定查询接入后，再执行相同命令，结果 `BUILD SUCCESSFUL`（50s；30 executed、107 up-to-date），**48 tests、0 failures、0 errors、0 skipped**。debug、未签名 release 和 AndroidTest APK 均生成。两变体 lint 为 **0 errors、9 warnings、1 hint**；均为既有依赖更新、授权 URI KTX 和 Compose 装箱提示，本步没有新增 lint 提示。

补充失败发布保留旧副本测试与 provider 目录覆盖后执行：

```bash
./gradlew :app:assembleDebugAndroidTest :app:lintDebug
```

结果 `BUILD SUCCESSFUL`（3s）。第一次设备执行发现 Kotlin 测试表达式返回 `assertThrows` 的异常对象，JUnit 要求 void 而拒绝整个 SQLite 测试类；只有 provider 的 5 项实际执行通过。将所有协程测试声明为 `runBlocking<Unit>` 后，重新执行上面的命令，`BUILD SUCCESSFUL`（2s），再重新安装和执行下述设备测试。该失败属于测试声明问题，不将第一次结果记为 SQLite 通过。

通过 SDK `apkanalyzer manifest application-id`／`manifest print` 核对三个实际 APK：debug 为 `io.github.chenxiex.calibrecloud.debug`，测试为 `.debug.test`，release 为 `io.github.chenxiex.calibrecloud`。两应用 minSdk 为 30，使用 `CalibreCloudApplication`；debug 可调试、release 不可调试，备份关闭；书籍 provider 不导出，authority 分别为 `.debug.books` 和 `.books`。没有新增权限或 provider 暴露范围。

| 最终产物 | SHA-256 |
| --- | --- |
| debug APK | `d0f81ee8c21e458e13fc893c14a15ce49da14187248c448d3e778faf26e94f80` |
| release 未签名 APK | `30af1d6c609d7f5442fbe59e72d9aa2f6139a7096af35bc178c3544ad81e3b63` |
| AndroidTest APK | `5c0c0fb27811bd4b97b06140f49d85e66eb399c5928df27c064c5785b2f1b491` |

`git diff --check` 与本次改动文档的本地链接检查通过；依赖及锁文件未修改。`plan.md` 仍被忽略，未暂存任何文件。

### PA6 真机平台测试

设备为 PA6、Android 14 / API 34，经已连接的 ADB 执行。仅安装已核对包标识的 debug 与 AndroidTest APK，没有安装正式包、访问正式应用数据或操作源书库。Fixture 是独立 UUID 命名的应用私有数据库与完整／暂存字节文件，不使用 Calibre 样本冒充本步尚未实现的导入或传输验证。

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 修正测试声明后重新安装测试包
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.state.ApplicationStateRepositoryTest,io.github.chenxiex.calibrecloud.files.BookFileProviderTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

最终结果 **OK (16 tests)**（2.615s），其中 11 项 SQLite／持久查询测试和 5 项 provider 测试。

| 验证路径 | 实际结果 |
| --- | --- |
| 数据库关闭重开 | 当前候选／绑定、最小清单、可空大小和源状态完整恢复 |
| 候选重选、过期验证、切回 | 新候选无 LibraryId；过期代号不能绑定；旧绑定保留，显式验证后可复用 |
| 同目录替换、同数字 ID、不同 UUID／格式／书库 | 各完整记录隔离；历史位置绑定可查询；分页顺序稳定 |
| 事务回滚 | 未提交的清单更新在重开后消失；不兼容身份改绑被拒绝，当前候选及旧绑定不变 |
| 本地配置适配器 | 旧偏好一次性导入；授权引用和候选原子更新；恢复本地授权配置不会覆盖已选 OneDrive 候选 |
| 完整发布门槛 | 只有暂存、文件缺失、空文件或已知长度不符均拒绝记录发布；失败替换仍可读旧文件代次 |
| 普通读取与清单 | 已确认源缺失的 OneDrive fixture 仍可读应用字节；记录与文件缺失可区分；损坏返回 CORRUPT_CONTENT，记录不被删除或改写源状态 |
| provider 隔离 | 实际 SQLite 数据库、暂存、封面、快照、索引及恢复目录不可提供；完整副本可读，路径穿越／符号链接逃逸被拒绝，debug authority 独立 |

上述读取器只连接持久清单与私有文件工厂，没有源或任务依赖；48 项 JVM 测试保留既有零源访问与后台 dispatcher 验证。长度与路径检查不是格式内容验证，真实传输处理器将在后续步骤提供该证据。

通过 `adb shell am start -W -n io.github.chenxiex.calibrecloud.debug/io.github.chenxiex.calibrecloud.ui.MainActivity` 执行冷启动，返回 `Status: ok`，随后进程仍存在；仅证明新 Application 容器下入口可启动，不作为墨水屏交互或性能验收。未操作系统目录选择器或微软登录。

记录后执行：

```bash
adb uninstall io.github.chenxiex.calibrecloud.debug.test
adb uninstall io.github.chenxiex.calibrecloud.debug
adb shell pm list packages io.github.chenxiex.calibrecloud.debug
```

两次卸载均返回 `Success`，包列表无匹配。没有卸载正式应用。构建、测试及脱敏探针输出保存在 `/tmp`，未加入版本控制。

### 当前验收状态

- 步骤 01 实现与必要自动检查、真实 SQLite 平台验证完成；**用户共同验收通过**：用户审阅本步交付后于 2026-10-06 明确要求“提交”，授权提交步骤 01。下列未执行项保持原证据范围，不改记为通过。
- 本步未重复真实系统目录授权／撤销、OneDrive 登录及墨水屏手工交互；第一阶段已确认项和条件补验保持原证据范围。真实本地文件访问与候选验证将在步骤 03 接入后验收，OneDrive 目录及真实文件访问在步骤 04，正式导入身份复用在步骤 05。
- 队列停放、界面打开意图、传输发布／清理并发、完整元数据清理和清单 UI 尚未实现；当前只提供选择代号及完整身份作为后续协调基础。AC01–AC03、AC09／AC10 的完整阶段验收仍未完成，不以本步 fixture 和入口启动替代后续真机路径。


## 步骤 02：持久任务提交与单任务调度（2026-10-06）

对应 R05、R14、R16–R18、R31、R33–R34。Schema v2 非破坏迁移增加任务、关系依赖、事务序号及候选授权会话字段，保留 v1 的书库绑定、当前选择、授权引用及最小清单。请求、来源、提升、阶段、结果、提交证据、暂存代次／源版本和重试期限可在重开数据库后恢复；持久化使用版本化显式标签与 code，没有 enum ordinal、凭据或任意暂存路径。

`DurableTaskQueue` 实现提交、等价请求去重／依赖提升、排序、观察及控制；`TaskCoordinator` 使用进程共享执行锁，筛选可执行请求，不抢占当前资源，在资源完成或安全暂停后重新选择。源提交前保存 Unknown，确定写入后立即保存 Confirmed；确认但未刷新或结果不明保留执行屏障，刷新失败重试从 WRITE_REFETCH 开始，未知结果先 RECOVERY_CHECK。旧的安全失败写回在后来相关意图开始后不可重试覆盖新意图；新的明确目标须重新提交到队尾。

首次配置发现可保存尚无稳定目录／LibraryId 的候选会话；发现后按选择代号、后端及授权会话 ID 原子补上位置。新会话、重选和候选取消撤销旧代号，迟到的配置结果不能覆盖当前选择。所有处理器须显式验证恢复版本／暂存并在支持阶段配合暂停／取消；有限自动重试最多三次，服务端等待与指数退避取较长者，永久错误不自动无限重放。

应用容器共享队列与协调器，但没有注册生产处理器、UI 提交入口或后台 worker。实际执行仅由测试注入处理器与条件 fixture 驱动，未访问本地／OneDrive 源文件，不执行真实写回，也不把 fixture 的完成当作后端交付。协议与后续处理器约束见 [任务模块](../src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md) 及相邻 KDoc。

### 构建、JVM 与静态检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

首轮结果 `BUILD SUCCESSFUL`（38s），48 项 JVM 测试全部通过。新增无稳定根目录的候选会话后，旧状态库测试因为 `LibrarySelection.location` 现在允许为空而编译失败；在使用已知位置的 fixture 中显式 `requireNotNull`，不改变生产行为，再执行上述基线，`BUILD SUCCESSFUL`（35s）。JVM 共 **48 tests、0 failures、0 errors、0 skipped**。lint 为 **0 errors、9 warnings、1 hint**，均为已有提示，没有新增 lint 项。实现初次编译还修正了 Kotlin 尾 lambda 参数位置，失败编译不计为通过。

首轮 PA6 执行队列 23 项、迁移 1 项及状态库回归 11 项，结果 **OK (35 tests)**（54.89s）；后续新增首次无目录发现和候选取消两项，最终结果记录如下。全部数据库为独立 UUID 命名的应用私有 fixture，源操作及提交证据由注入处理器提供，不访问真实书库或正式应用数据。


最终收紧候选请求不能发布书籍／导入缓存事件后执行：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

结果 `BUILD SUCCESSFUL`（33s），lint 数量不变。没有修改 Manifest、权限、依赖或锁文件；本步未执行 release 构建／lint，未将历史 release 产物算作本步结果。实际 debug APK 的包名、可调试标记、非导出书籍 provider 与独立 `.debug.books` authority 已通过 `apkanalyzer manifest print` 核对；AndroidTest 为独立 `.debug.test` 包。

| 最终产物 | SHA-256 |
| --- | --- |
| debug APK | `fb429b1741abbef40ab8e78c31847778cc93cbafdd5e4416c30d5fd7ae156f7e` |
| AndroidTest APK | `73e4036d0eedca4cb8a70949c00343ff073e41fd5e2ac7ae59b87d31c7d0e4bd` |

### PA6 最终平台验证

设备为已连接的 PA6、Android 14 / API 34。仅安装上述已核对的 debug／AndroidTest 包；所有 fixture 均在 debug 应用私有目录，没有登录账号、访问源文件、覆盖正式应用或使用正式数据。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.tasks.DurableTaskQueueTest,io.github.chenxiex.calibrecloud.tasks.TaskSchemaMigrationTest,io.github.chenxiex.calibrecloud.state.ApplicationStateRepositoryTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

最终结果 **OK (37 tests)**（85.153s）：25 项队列／协调器、1 项 v1 → v2 迁移及 11 项既有状态库回归测试。

| 验证路径 | 实际结果 |
| --- | --- |
| 持久提交与恢复 | 重开后保留来源、提升、优先级、序号、固定书籍集合、源版本和提交证据；新序号继续递增；v1 数据及授权引用保留，外键检查通过 |
| 去重与提升 | 并发等价提交只创建一个请求；用户请求提升已有自动请求及必要依赖，不越过更早高优先级请求；不同版本前提、目标值或写后新鲜度不合并 |
| 资格与依赖 | 跨库或错误提交阶段依赖被拒绝且不留半事务；等待网络／依赖不阻塞无关可执行工作；依赖只允许指向既有任务，没有建立循环的修改入口 |
| 单执行与批次 | 两个协调器并发唤醒最大同时执行数为 1；低优先级资源不中途抢占，结束后先执行后来用户请求，再执行剩余自动子任务 |
| 写回协议 | 相反目标按相关意图顺序等待；Unknown 不放行后继；Confirmed 保留刷新执行权且写后同步不复用旧快照；刷新失败只重试 refetch/import，不重复提交；源提交拒绝暂停／取消 |
| 旧意图与书库切换 | 后来相关写回开始后旧未提交失败任务不可重试，新意图重新排队；后来任务尚未开始时可安全重试旧任务；旧书库的提交证据保留且不阻塞新书库独立工作 |
| 控制与恢复 | 处理器在支持阶段暂停／取消后关闭资源并停止发布；进程中断保留暂存版本和阶段，恢复先由处理器核验；未知提交先恢复检查再刷新 |
| 等待与重试 | 服务端延迟和指数退避取较长者，重开仍遵守期限，最多三次自动重试；永久授权错误只执行一次，需显式重试；LOGIN 等真实等待原因不会被本轮资格排除覆盖 |
| 候选配置 | 首次无绑定、无稳定目录仍可调度发现；会话及随后位置可持久恢复，不分配 LibraryId；重选、账号会话改变和取消后旧 context 不能提交、解析位置或完成旧验证 |
| 事件与现有状态回归 | 非最终阶段不能发 CacheChanged，最终完整发布事件在持久完成之后；11 项状态库测试继续通过，最小清单和原授权适配不受队列迁移影响 |

测试注入的恢复决定、写入确认和缓存发布只是执行协议证据，不证明实际源版本检查、真实写入、完整缓存传输或后台行为。处理器在后续步骤实现时仍需补上对应真实后端验证。

记录结果后执行：

```bash
adb uninstall io.github.chenxiex.calibrecloud.debug.test
adb uninstall io.github.chenxiex.calibrecloud.debug
adb shell pm list packages io.github.chenxiex.calibrecloud.debug
```

两次卸载均返回 `Success`，包列表无匹配。`git diff --check` 与受影响文档的本地链接检查通过，依赖／锁文件未改，暂存区为空，`plan.md` 仍被忽略。构建及测试输出保存在 `/tmp`，未加入版本控制。

### 当前验收状态

- 步骤 02 实现、基线检查与必要真实 SQLite 平台验证完成，**用户共同验收通过**：用户审阅本步交付后于 2026-10-06 明确要求“提交”，授权提交步骤 02。验收范围为调度／依赖／写后刷新顺序、真实持久状态、候选上下文与阶段恢复证据；下列后续验收仍保持未完成。
- 后台、设备重启、通知及任务 UI 留步骤 09；首次本地／OneDrive 真实源访问、配置发现及版本核验分别随步骤 03／04 接入。真实写回与 write-refetch 联动仍留第四阶段；本步不关闭这些验收。
- 没有继续步骤 03，没有创建生产假后端、假写回完成或本地已读覆盖。

## 步骤 03：本地 SAF 文件后端与一致快照（2026-10-06）

对应 R04–R07、R09、R31–R35。本步实现与必要自动／PA6 内部存储验证完成，用户于 2026-10-06 共同验收通过并明确要求提交；未开始步骤 04。

### 实现与边界

本地后端通过受支持系统提供方的 tree URI、document ID 和只读流逐级解析根内文件，拒绝危险逻辑路径、树外定位、同名歧义和不支持提供方。实际持久读权限与提供方写入 flags 分开；可读取或 flags 可写都不证明第四阶段安全提交能力。书籍与封面可通过后端解析和只读获取，尚未接入副本传输或封面缓存。

版本为完整内容 SHA-256，不依赖 provider 时间字段或书籍修改时间。快照检查非空 WAL／rollback journal、SHM 和多库 journal，复制并 fsync 私有暂存，再完整重读源、复查定位与日志、比较内容哈希，在私有文件上以 `OPEN_READONLY` 执行 `integrity_check`。无法确认一致性时明确拒绝；不对源执行 checkpoint、迁移、修复或写入。这是保守变化检测，不保证任意并发写入下的原子快照；已知存在日志的书库须由源端先安全关闭／整合再显式重试。依据见 [SQLite 数据库及事务日志边界](https://www.sqlite.org/howtocorrupt.html) 和 [Android SAF 授权与能力](https://developer.android.com/training/data-storage/shared/documents-files)。

成功文件按任务 ID 和不可变尝试代次保存于私有 `filesDir/snapshots/local`，失败只清理本次暂存，保留旧成功快照，不进入书籍 provider。候选处理器使用当前选择代号，复制块及发布前检查控制／重选；恢复重新获取源，不凭暂存或 Running 判成功。取消使旧代号失效，再次验证取得新上下文。完成不分配 LibraryId、不导入、不发 `CacheChanged`。静态入口显示排队／执行／暂停／错误与“有效 SQLite 快照，待导入；尚未验证 Calibre 结构”，提供显式加载及当前阶段控制；页面恢复仅查私有状态，不隐式打开源。

实现契约见 [本地后端](../src/main/java/io/github/chenxiex/calibrecloud/storage/local/AGENTS.md) 与 [任务模块](../src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)，操作说明见 [本地入口](../README.md#本地目录授权)。没有新增产品决策、权限、Manifest、数据库 schema、依赖或锁文件变更。

### 自动检查

最终运行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

结果 `BUILD SUCCESSFUL`（38s）；JVM **56 tests、0 failures、0 errors、0 skipped**，其中新增后端 8 项覆盖安全解析、明确缺失／越界／撤权错误、内容版本、旧快照保留、读取中断、读取期间变化、日志及控制异常清理。lint **0 errors、9 warnings、1 hint**，与步骤 02 数量相同；新增 `UseKtx` 提示已修正。未影响 release 特有行为，本步没有执行 release 构建／lint，不引用历史产物作为本步通过证据。

过程中首轮因 Kotlin typealias 无法限定 sealed 子类型导致 handler 编译失败，改为直接引用 `LocalSourceResult.Available/Failed` 后通过。一次 lint 分析发生 Kotlin FIR/UAST 内部异常；随后完整重跑通过，没有屏蔽 lint 项、更新依赖或锁文件。初次平台 SQLite fixture 断言“目录中不存在事务日志”失败；修正 fixture 为显式 DELETE journal 模式，并核对校验前后已有文件及字节完全不变，生产行为未为测试修改。后续定向 5 项平台检查通过，最终下列 6 项也全部通过。失败尝试不计为通过。

实际 debug Manifest 已用 `apkanalyzer manifest print` 核对：包为 `io.github.chenxiex.calibrecloud.debug`、可调试、书籍 provider 非导出且 authority 为独立 `.debug.books`；AndroidTest 独立包标识经 `apkanalyzer manifest application-id` 核对。

| 最终产物 | SHA-256 |
| --- | --- |
| debug APK | `f2a88e9a2b4ada8a6a24f92f392fadc441257378d64004f5714d2ba5e8af5d5d` |
| AndroidTest APK | `520f65736c6a7e7631fca68e0026568967b12cd834743d33a2d4717ad62cae88` |

### PA6 内部存储与平台验证

设备 PA6、Android 14 / API 34。将仓库样本复制至本次专用目录 `/sdcard/Documents/calibre-cloud-step03-20261006`，没有操作已有书库或正式应用数据。安装已核对的独立 debug／AndroidTest 包后，通过真实系统选择器进入该专用目录并确认授权；不通过注入 grant 代替系统 SAF。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.storage.local.LocalSourceBackendDeviceTest,io.github.chenxiex.calibrecloud.tasks.LocalSnapshotTaskHandlerTest,io.github.chenxiex.calibrecloud.storage.local.LocalSnapshotIntegrityTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

最终 **OK (6 tests)**（30.35s）：1 项真实 SAF 设备测试、4 项真实持久队列／生产候选处理器集成、1 项真实私有 SQLite 检查。

| 路径 | 实际结果 |
| --- | --- |
| 真实 SAF 文件访问 | 根内数据库、样本 EPUB 和封面只读取得；不存在文件返回 SOURCE_MISSING；私有快照 SQLite 完整性通过，内容版本与前后源一致 |
| 真实读取取消／撤权 | 实际 SAF 复制期间取消回调传播，暂存清理且旧快照存在；撤销持久权限后返回 AUTHORIZATION_EXPIRED，私有旧快照保留，授权组件显示需重新授权 |
| 候选完成 | 真实 SQLite 队列与生产 handler 完成；不绑定身份、不发 CacheChanged，源读两次；该集成部分注入文档，不宣称其为 SAF 提供方证据 |
| 取消／重选隔离 | 在确定性读取阻塞处发出取消或重新选择；流关闭、暂存清理、旧任务终态取消，旧 context 不能提交，新 context 可完成且不污染当前配置 |
| 重开与恢复 | 保存 Running/checkpoint 后重开应用状态库，恢复重新读取已变化源，不信任旧 checkpoint；成功后清空 checkpoint，仍不激活书库 |
| 私有 SQLite | 有效数据库通过、损坏候选拒绝且不删除；完整文件列表及所有字节在校验前后不变 |

另外实际操作首页“验证／加载快照”，观察成功“待导入”文字；强行停止后重新打开，状态恢复且私有快照文件清单没有新增，证明此次恢复没有重新取得快照。向专用测试副本添加非空测试 `metadata.db-wal` 后再次加载，观察一致性冲突提示和重试按钮；旧成功快照文件清单不变。移除该测试日志并点击重试，首次即时采样仍处于真实执行阶段，未把它记为成功；随后持久记录核对三项实际候选任务均为 Completed，确认该重试最终完成。撤权后重新打开首页，观察需重新授权及旧“待导入”状态，加载按钮禁用。UI 节点检查无可滚动应用节点，文字／按钮位于可见页；这是本次静态入口观察，不替代用户对全部墨水屏交互的共同验收。

操作前后分别枚举测试源所有文件计算 SHA-256；移除临时日志后用 `cmp` 核对，两份清单完全一致。真实 SAF 设备测试亦核对源数据库内容版本，应用没有修改源文件。日志与临时采样仅保存在 `/tmp`，未加入版本控制。

记录后卸载并清理：

```bash
adb uninstall io.github.chenxiex.calibrecloud.debug.test
adb uninstall io.github.chenxiex.calibrecloud.debug
adb shell pm list packages io.github.chenxiex.calibrecloud.debug
adb shell rm -r /sdcard/Documents/calibre-cloud-step03-20261006
adb shell rm -f /sdcard/window-step03.xml
```

两次卸载均为 `Success`，包列表无匹配；只删除本次创建的测试目录和 UI 采样文件。

### 当前验收状态

- 步骤 03 实现、必要基线与 PA6 内部存储 SAF 验证完成；**用户共同验收通过**：用户于 2026-10-06 审阅交付后明确要求“提交”，授权提交本步。验收范围为本地只读源访问、一致快照、候选任务控制／隔离／恢复及实际 PA6 内部存储证据；下列条件补验保持未完成。`plan.md` 保持忽略，不加入提交；没有开始步骤 04。
- SD 卡／其它已支持本地存储卷在本次没有提供专用 fixture，保持条件补验未完成；未知 OEM 和云提供方仍不支持。大规模真实书库响应与较长任务的人工暂停／继续交互没有本步代表性样本证据。
- Calibre 结构、动态栏目、完整索引与原子书库激活留步骤 05；副本完整传输、封面发布、缓存清理、后台恢复及阅读器分别留后续步骤；第四阶段安全写回能力未实现。本步不关闭完整 AC01／AC03、后台或源提交验收。

交付静态检查：`git diff --check`、全部本步修改／新增文档的本地链接与空白检查通过；书籍 provider 路径仍仅为 `books/`。暂存区为空，`plan.md` 经 `git check-ignore` 确认为忽略，依赖及锁文件未改。

## 步骤 04：个人 OneDrive 目录、只读后端与快照（2026-10-06）

当前状态：实现、自动／平台检查与用户手动验收已通过；最终验收和设备收尾见本节末尾。下文早期失败及待验收状态保留为历史记录，以末尾结论为准。

对应 R03–R06、R08–R09、R31–R35。本步实现与自动／平台检查完成，**真实 Graph 与用户共同验收尚未完成，未提交**。没有开始步骤 05。

### 实现与边界

应用容器共享加密授权组件。可信后端按需取得有效令牌，过期或 Graph 401 时通过 AppAuth 刷新；临时网络／服务端失败保留刷新凭据，失效授权要求重新登录。登录会话 UUID 与原 AuthState 一同加密保存，刷新保持、成功重新登录换代、旧 envelope 迁移。源任务携带预期会话，授权互斥锁内核对代次后才取得令牌，避免旧任务在并发重新登录后使用新账号令牌；不新增凭据存储。

只读后端从本人 `/me/drive` 取得 `owner.user.id`、drive ID 与根 item ID，只接受个人 drive，拒绝共享／remote 项目。目录分页校验立即父级、drive 与祖先链；源路径逐级解析到选定根内，定位 ID 拒绝路径归一化片段与分隔符。文件内容版本使用 cTag，eTag／显示名称／时间不作为内容版本。Graph nextLink 只允许同一 HTTPS children 端点，任务只保存目录 item ID 与页码；预签名内容 URL 不进入数据库、浏览结果、日志或 reader。重定向使用独立无授权客户端，拒绝降级 HTTP 和带用户凭据的 URL；不实现上传、源删除或写回。

目录发现／浏览／分页和快照均进入共享持久候选队列。私有浏览结果只包含稳定账号／drive／item ID、目录名及页码，恢复查询不联网。UI 分为本地授权、OneDrive 授权、目录与候选任务四页，每页最多三个目录，显示当前目录名称，显式选择根；导航链仅在请求成功后变更。浏览失败可继续显示当前选择代次内的上一有效结果；取消／重选／会话变化不会显示旧候选结果。根选择和已保存位置的重新授权以选择代号校验事务换代，过期操作不能覆盖较新的本地或云端配置。目录选择不分配 LibraryId，不激活书库。

云快照复用本地私有 SQLite validator，检查 WAL／journal／SHM 与多库 journal，复制并 fsync 私有暂存，前后多次比较 item ID／cTag，第二次完整读取比较 SHA-256，并检查已知大小。日志冲突、源变化、中断或损坏只清理本次 part，旧成功代次保留。成功文件位于 `filesDir/snapshots/onedrive/<task UUID>/<attempt UUID>.db`，不进入书籍 provider，成功只表示 SQLite 快照有效，Calibre 结构验证与完整导入留步骤 05。这是保守变化检测，不保证任意并发源写入安全，也不证明第四阶段条件提交能力。

实现与官方端点依据见 [OneDrive 后端约束](../src/main/java/io/github/chenxiex/calibrecloud/storage/onedrive/AGENTS.md)、[授权约束](../src/main/java/io/github/chenxiex/calibrecloud/auth/AGENTS.md) 和 [任务约束](../src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)。操作说明见 [OneDrive 配置与入口](../README.md#onedrive-配置)。没有新增产品决策、schema 版本、权限、依赖或锁文件变更；候选请求新增目录 ID／页码使用向后兼容缺省解析。

### 自动检查

运行以下完整基线（最终代码版本）：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

结果 `BUILD SUCCESSFUL`（1m 8s）；JVM **68 tests、0 failures、0 errors、0 skipped**，新增 OneDrive 后端 12 项使用真实 OkHttp Request／Response 的注入 fixture，覆盖个人身份／换账号、分页及恶意 nextLink、根边界／共享项目／明确缺失、路径 ID 归一化拒绝、401 刷新、无凭据内容重定向、429／503／Retry-After、版本变化、事务日志、读流中断、损坏快照与取消清理。JVM fixture 注入 JSON decoder，Android 的实际 org.json 解码另由下面的平台测试覆盖。

首轮编译发现私有目录恢复时遗漏 `OneDriveItem` 三个字段，补齐后通过。新增定位边界测试首次失败，补齐 opaque ID 中分隔符的拒绝后通过。失败尝试不记为通过。最终代码版 lint 为 0 errors；随后删除四页入口不再使用的两项旧分页字符串，并定向重建两变体／AndroidTest 与重跑两变体 lint，结果见下方交付检查；不屏蔽 lint、不更新依赖锁。

### PA6 平台与静态入口检查

设备 PA6、Android 14 / API 34，在线。当前 `local.properties` 三项 OAuth 属性非空，未读取／输出其值、未修改用户配置；完整配置不等于真实账号或 Graph 验收通过。安装前用 `apkanalyzer` 核对独立 `.debug`／`.debug.test` application ID；实际 Manifest 核对 debug 可调试、书籍 provider 非导出且 authority 为 `.debug.books`。

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationTest,io.github.chenxiex.calibrecloud.tasks.OneDriveCandidateTaskHandlerTest,io.github.chenxiex.calibrecloud.tasks.LocalSnapshotTaskHandlerTest,io.github.chenxiex.calibrecloud.storage.local.LocalSnapshotIntegrityTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

最终 **OK (27 tests)**（10.776s）：17 项授权、5 项 OneDrive 候选队列集成、4 项本地候选回归、1 项真实私有 SQLite 完整性。此前 26 项也通过，新增预期登录会话测试后执行上述最终 27 项。

| 路径 | 实际结果 |
| --- | --- |
| 可信授权获取 | 有效缓存 token 不刷新；过期／强制刷新保持会话；恢复及旧 envelope 迁移保留会话；新登录换代；网络／服务端失败保留凭据可重试，invalid_grant 要求重新登录；取消传播；旧会话不能读取新 token 或触发刷新 |
| 真实 SQLite 与 JSON | 生产 service／handler、真实状态库／队列及 org.json decoder 配合注入 OAuth／HTTP 响应，目录末页及名称持久恢复，恢复查询零 Graph；只保存稳定 ID／名称，无 token、nextLink 或 HTTPS URL |
| 候选选择隔离 | 非当前列出的 item 被拒绝；选择保存账号／drive／根 ID，不激活书库；旧浏览选择及旧重新授权不能覆盖较新的本地选择；登录换代后旧任务在发 Graph 前失败 |
| 快照与恢复 | 私有云快照完成两遍源读取且不激活书库；重开保存位置与完成状态；Running 目录请求重开后从持久页码重新执行。此处数据库内容／validator 为 fixture，不宣称真实云端 Calibre SQLite 验证 |
| 既有本地路径 | 生产候选处理器完成／取消／重选／重开恢复及真实私有 SQLite validator 的 5 项回归通过，不访问真实书库 |

此批平台检查不访问真实 Graph、SAF 源或真实账号，所有可写数据库和输出均为应用私有独立 fixture。

实际启动 debug 首页，经 ADB 操作四页分页入口：未登录状态、目录尚未选择与任务尚未提交文案可见，所有页面无 scrollable 应用节点，按钮／文字在 PA6 可见范围内。首次紧接启动的 UI 采样返回 null root，等待页面就绪后重新采样成功，不将失败采样记为通过。检查时没有发起真实登录、选择真实云盘目录或修改源书库。此观察不替代有目录列表／长任务的墨水屏交互共同验收。

用于上述最终 27 项平台测试的产物（后续仅移除无引用旧分页资源）：

| 测试产物 | SHA-256 |
| --- | --- |
| debug APK | `77f8db8a0737847e14c11edf424cffdbaa1c6d8025b3074c33e6e83a4cb4f09f` |
| AndroidTest APK | `30d10cc8d4cd06d281680a79901a5dc5d0bb4c9de6b61179fdd4dd6006024878` |

### 待完成验收

- 需要用户在独立 debug 包内完成个人测试账号登录，并提供专用 OneDrive 测试书库目录（从仓库样本准备独立副本，不能选择真实书库）。本次安装后的生产授权状态为未登录，未提供已确认的专用云端目录；不索取密码、token 或 client secret，不自行访问真实书库。
- 尚未执行真实 Graph 目录分页、稳定账号／drive 校验、数据库／格式／封面读取、cTag 前后核验、断网后旧快照保留与重新登录／重试恢复，以及对应目录／错误／控制入口的用户墨水屏验收。这些项目保持未完成；配置、fixture 和平台测试不替代它们。
- 第一阶段无浏览器场景的条件补验保持原证据范围；本步不补写为通过。导入、动态栏目、离线完整副本、传输／清理／后台／阅读器与源写回继续留后续阶段。
- 按 plan.md 推进门槛，真实必要项及用户共同验收通过后才提交步骤 04；当前未提交、未开始步骤 05。

### 交付检查与清理

删除无引用旧分页资源后运行：

```bash
./gradlew :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

`BUILD SUCCESSFUL`（47s）；debug／release lint 均为 **0 errors、9 warnings、1 hint**，数量与既有 debug 基线相同。这次仅资源清理，没有改变上述 27 项平台测试覆盖的生产逻辑；没有再次扩展平台测试范围。最终 debug APK SHA-256 为 `174faecf1e1a41f2b13d89467805a97ab18a891d3962de900e3d3f2dc4cffd71`，AndroidTest APK 与上表一致，release 未签名 APK 为 `b4755c172213b38f7fb2bda44f64be2b370849e7fc2d78d85a29c6bee0e6c369`。

记录后执行：

```bash
adb uninstall io.github.chenxiex.calibrecloud.debug.test
adb uninstall io.github.chenxiex.calibrecloud.debug
adb shell pm list packages io.github.chenxiex.calibrecloud.debug
adb shell rm -f /sdcard/window-step04.xml
```

两次卸载均返回 `Success`，包列表无匹配；只清理本次 UI 采样文件，未操作正式应用或源书库。构建／平台测试／UI 采样输出保存在 `/tmp`，未加入版本控制。`git diff --check`、本步修改／新增文档的本地链接检查通过；暂存区为空，`plan.md` 仍被忽略，构建文件及依赖锁未改。

### 重新安装供用户实机验证（2026-10-06）

用户要求安装调试包并提供真机指引。PA6 在线；核对现有 APK SHA-256 与上述最终 debug 产物一致（`174faecf1e1a41f2b13d89467805a97ab18a891d3962de900e3d3f2dc4cffd71`），实际 Manifest 的 application ID 为 `io.github.chenxiex.calibrecloud.debug`、可调试、书籍 provider 非导出且使用独立 `.debug.books` authority。没有代码变化，本次未重跑构建／测试。

执行 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 返回 `Success`，随后启动 debug `MainActivity`。只安装 debug 应用，未安装正式包或 AndroidTest 包；按用户要求保留应用用于手工验收，待记录验收结果后卸载。当前仍未将任何真实 Graph 项目记为通过。

在 `/tmp/calibre-cloud-step04-test-20261006.zip` 准备仓库样本的独立测试副本：顶层 `calibre-cloud-step04-test-20261006` 下有 `library-a`、`library-b` 两个完整样本副本，以及 `missing-db`、`navigation-only` 两个仅含说明文件的目录。后两个目录用于分页和缺失数据库检查；样本原件未修改。此 ZIP 未上传 OneDrive，用户须自行解压并上传整个顶层目录至个人测试账号，待同步完成后测试；不能直接上传 ZIP 来代替目录，也不能选择真实书库。

手工顺序及预期：

1. 在第 2 / 4 页登录个人测试账号，返回后显示已授权。
2. 在第 3 / 4 页“浏览／切换到 OneDrive”，进入专用测试顶层目录；应有当前目录名称，每页最多三个子目录，能翻至包含第四个目录的末页，末页下一页禁用；进入目录／返回上层可用，文字与控件无滑动／动画且在墨水屏上可读。
3. 进入 `library-a`，点击“选择当前目录为书库”；第 4 / 4 页点击“验证／加载快照”，最终应显示“已取得有效 SQLite 快照，待导入；尚未验证 Calibre 结构”。此阶段不会出现图书列表。
4. 从系统设置强行停止 debug 应用并重新打开；登录、所选目录及已完成任务状态应恢复，打开页面不自动重新取得快照。
5. 保留已选 `library-a`，关闭 Wi-Fi 后再次加载；应显示网络等待／错误，配置保留。恢复 Wi-Fi，等待至少 30 秒后使用“执行排队任务”（等待状态）或“重试”（失败状态），应完成。旧快照是否保留及版本核验留 agent 后续检查，不能仅靠 UI 文案证明。
6. 重新浏览，进入 `missing-db` 并明确选择它，再加载；应报告目录或 `metadata.db` 不存在。随后重选 `library-a` 或 `library-b`，加载应恢复成功。
7. 第 2 页重新登录同一测试账号，再加载已选测试目录，应可恢复；能够实际观察到运行中任务时测试暂停／继续／取消。小样本过快无法操作时如实记未测，不能将任务结束视为控制通过。
8. 仅在专用 `library-b` 测试副本中，上传一个非空 `metadata.db-wal`（可为纯文本测试文件，确保 OneDrive 文件名完整且同步结束）；选择该库加载应显示事务日志／一致性冲突。删除本次添加的测试文件并等待云端同步后重试，应恢复成功。不能在真实书库制造日志。

用户回报各项通过／失败／未测及应用提示后，再核对真实源请求、私有快照、内容版本、源未修改及必要技术补验；目前不将登录／浏览／快照／错误／控制或共同验收记为通过。不采集账号、密码、令牌或完整回调 URL。

### 实机浏览故障修复与复验（2026-10-06）

用户回报第 1 项登录完成、显示已授权；第 2 项点击“浏览／切换到 OneDrive”没有反应，页面翻页正常。第 1 项真实登录按用户确认记录通过，第 2 项原版本失败，不将其记为共同验收通过。

读取独立 debug 应用的持久任务（只检查固定状态／错误类别）确认点击已提交，多个浏览任务以 `UNSUPPORTED_OPERATION` 结束；目录页没有就近展示失败，因而用户看不到反馈。通过固定阶段诊断确认真实本人个人 drive 响应未提供可用的 `owner.user.id`，错误来自必填 owner ID 的工程假设，不是登录失败。诊断仅输出 `stage=owner_id` 等固定标签／数字 HTTP 状态，不包含响应正文、账号／目录标识、路径、凭据或异常全文。

先添加 owner ID 缺失、提供已授权 subject 的 JVM 回归测试，定向运行稳定复现失败（1 test、1 failed）；修复后通过。后端优先使用 Microsoft consumers 登录流程中 AppAuth 解析的稳定 ID token subject，并加固定来源前缀；不从显示名、邮件地址或 drive ID 猜账号身份。账号 subject 同原加密 envelope 及登录会话保存，刷新省略 ID token 时仍保持，恢复可迁移，新登录重新取值，旧会话不能读取新账号 subject。不改变 OAuth scopes、不新增 credential store；可信 fixture 未提供 subject 时仍可使用返回的 owner ID，两种标识不能直接视为相等。

目录页直接显示浏览任务排队、执行、等待、失败与完成状态，快照完成不冒充目录成功。返回上层与选择书库按钮放在同一行，以保留三目录布局和触控高度。新增 4 项状态回归；授权新增 5 项平台测试覆盖 subject 解析、恢复、会话隔离、缺 ID token、刷新省略 ID token及旧 envelope 迁移。

实际运行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:assembleRelease :app:lintDebug :app:lintRelease
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.auth.OneDriveAuthorizationTest,io.github.chenxiex.calibrecloud.tasks.OneDriveCandidateTaskHandlerTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

结果两次构建均 `BUILD SUCCESSFUL`（9s、1m 22s）；JVM **73 tests、0 failures、0 errors、0 skipped**，平台 **OK (27 tests)**（34.553s，22 项授权及 5 项候选集成），两变体 lint 均 **0 errors、9 warnings、1 hint**。

更新安装 debug APK 返回 `Success`，没有卸载或清除用户刚完成的登录。当前 debug APK SHA-256：`b07579a9a50e9f413ab6d679688670b453717e34b8f8a8dd0e826a5a1938d30c`。平台测试仅使用独立私有 fixture，不覆盖生产授权；结束后卸载 `.debug.test` 返回 `Success`，按用户手工验收请求继续保留 `.debug`。

在现有真实登录状态下，显式点击目录浏览，经生产持久队列与后端取得本人 drive 及根目录第一页。实际任务最终 `Completed`，私有结果包含 **3 个目录、hasNext=true**，没有 token／nextLink 等凭据字段。页面可见当前目录与“目录浏览已完成”提示，返回上层／选择书库按钮在 PA6 可见区域（文字纵坐标 1118–1173，页面导航从 1248 开始），无 scrollable 应用节点。仅查询目录元数据，未打开任何真实书库文件、未写入源；没有将账号、根目录名称或 ID 输出到日志。启动期间一次误打开系统本地选择器，随即取消，未确认授权或更改本地选择。

此证据仅补上真实个人 drive 身份发现和根目录第一页浏览；专用测试目录的分页／选择、真实数据库快照、断网／缺失／日志冲突恢复、格式／封面技术项及用户共同验收仍待完成。用户可从当前第 3 页继续原第 2 项验收。步骤 04 未提交，未开始步骤 05。


### 完整目录加载与第三页错误修复（2026-10-06）

用户明确调整 R08：进入目录时完整加载子项目，翻页不再等待网络。已先同步 [spec.md](../../spec.md) 与计划，再实施。这里的“一次加载”是一次候选任务；若 Graph 返回 nextLink，任务必须取完服务器分页，并不承诺任意大小目录只有一条 HTTP 请求。当前四页入口为第二阶段临时验证 UI，每页三个目录用于覆盖分页路径；第三阶段最终 UI 不固定三个目录，具体布局随该阶段实现。

第三页报“不支持此云盘或目录”的真实脱敏诊断为 `shared_remote_deleted_item`。原实现将 `shared` 一概当成外部共享项而拒绝整个列表；实际上本人 drive 中向他人共享的项目也有该字段。现在仍核对本人 drive、立即父级与祖先链，允许本人拥有的 shared 项；浏览跳过文件、remoteItem、删除项及 package，其他不支持项不再使整个目录失败，源访问的边界保持严格。新增回归 `unrelatedUnsupportedChildrenDoNotPreventDirectoryBrowsing` 修复前实际失败（ClassCastException），记录 `/tmp/calibre-step04-third-page-repro.log`。后续首轮测试另发现两处 fixture 断言错误（父名称与协程复制取消异常），已修正测试，未为断言更改生产行为。

进入目录／返回上层显式提交完整加载任务，所有服务器页完成且控制／会话检查通过才原子保存完整结果；中断不发布半页。旧结果缺少 complete 标志时不用于本地分页。UI 从完整结果切片，翻页不提交任务、不唤醒协调器、不访问 Graph；恢复只读私有结果。新增集成测试覆盖 14 个目录、多服务器页、本地第三页／末页、页越界、恢复与 ViewModel 翻页前后队列／HTTP 请求数不变。


本次检查：`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease` 为 `BUILD SUCCESSFUL`（1m 14s），JVM **79 tests、0 failures、0 errors、0 skipped**，两变体 lint **0 errors、9 warnings**（既有 hint 保留）。真机执行 `adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.tasks.OneDriveCandidateTaskHandlerTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner` 为 **OK (6 tests)**（38.279s）；独立 fixture 验证完整加载及 UI 本地分页。日志分别为 `/tmp/calibre-step04-local-pagination-build.log`、`/tmp/calibre-step04-local-pagination-device-tests.log`。debug／test APK application ID 核对为独立 `.debug`／`.debug.test`，更新安装成功，测试后卸载 test 包成功；按用户要求保留 debug 与真实登录。debug APK SHA-256：`0300f08df95202ea180010121a0d454868b525af2df80a0341c565dc9ea07b81`。


真实 PA6 根目录复验：保留现有授权后显式浏览，生产任务 `Completed`，私有完整结果 **14 个目录、complete=true**。ADB 操作本地翻到 **目录列表第 3 页** 和 **第 5 页（末页）**；第三页正常显示，末页目录“下一页”按钮实际 clickable 节点为 `enabled=false`。翻页前后持久队列均 **27 项**，最新任务 ID 与 Completed 状态不变，未创建目录翻页任务；无 scrollable 应用节点。采样时一次 UI dump／传输较慢，完成后读到有效页面，不将传输耗时当作翻页耗时。本次仅读取目录元数据，没有打开真实书库文件或写入源；账号、目录名称与 ID 未输出。用户仍需继续专用测试书库选择、快照和失败恢复等原有验收；步骤 04 未提交，未开始步骤 05。


### 用户验收通过与步骤收尾（2026-10-06）

对应 R03–R06、R08–R09、R31–R35 的本步路径。用户在完整目录加载／第三页修复版本上明确确认“全部手动测试通过”，据此记录上述第 1–8 项手动清单及墨水屏交互共同验收通过，包括登录、目录导航／本地分页、专用测试书库选择与快照、重启恢复、断网后恢复、缺失数据库后重选恢复、重新登录与任务控制，以及测试 WAL 冲突与清理后重试。该结论是用户反馈，不改写成 agent 逐项自动操作的结果。

提交前以只读方式核对 PA6 的持久任务与应用私有产物：真实 OneDrive 快照任务有 **5 次 Completed**；取出这 5 次成功任务的私有 `.db`，全部 `PRAGMA integrity_check=ok` 且包含 `books` 表，内容散列一致。此检查证实真实快照产物可读，不等于步骤 05 的 Calibre schema／栏目导入验收。没有读取授权密文、采集账号或输出书库内容，没有发出新的 Graph 请求或源写入；错误历史保留，不把旧失败改成成功。源未修改依赖本步只读请求边界与测试覆盖；未取得云端源前后散列，不声称已独立完成源前后散列对比。格式／封面真实传输随步骤 06／07 验证，旧导入和副本离线可用性随步骤 05／06 验证；第一阶段既有条件补验不因本次确认而关闭。

有界只读提交审查未发现阻塞问题；本步构建、79 项 JVM、6 项最新候选平台测试及此前 22 项授权平台测试的证据保持上述实际执行范围。收尾仅修改验收文档与计划，执行 `git diff --check` 和本地文档链接／暂存范围检查。`plan.md`、`local.properties`、日志、APK 和测试数据库不加入提交，依赖锁未变化。

手工验收已结束，执行 `adb uninstall io.github.chenxiex.calibrecloud.debug` 返回 `Success`，随后 `adb shell pm list packages io.github.chenxiex.calibrecloud` 无匹配；测试包已在上一轮卸载。正式应用及源测试目录未操作。本步共同验收通过并按既定计划提交；步骤 05 保持待实施。

## 步骤 05：完整导入、动态栏目与书库激活（2026-10-06）

对应 R03–R05、R09、R13、R22–R25 的导入数据基础、R31–R36。实现、自动验证及八组共同真机操作已完成，用户于 2026-10-06 明确要求提交本步；缺少样本的补验保持未完成，详见本节末尾。不开展步骤 06。

### 实现范围

两后端的持久候选同步进入同一只读 Calibre 解析器，验证 SQLite 完整性、必需结构、关联、UUID、路径、格式与支持的栏目布局，完整导入书籍、作者、加入时间、评分、丛书／序号、标签、可展示简介、格式／大小与相对定位。动态发现布尔、文本、枚举、多值文本；未知／计算栏目只保存定义，不执行模板。结构依据及长期开发契约见 [元数据模块](../src/main/java/io/github/chenxiex/calibrecloud/metadata/AGENTS.md)。

Schema v3 非破坏性增加完整导入与书籍身份索引，保留 v1/v2 配置、清单、队列、依赖、序号与恢复检查点。独立私有代次完成快照与索引后，事务同时发布书库绑定、导入引用、完整书籍索引与任务完成状态；失败保留旧有效导入，未发布 UUID 目录在下一次导入安全回收。只在完整提交后发缓存事件，不访问／修改 Calibre 源库。

位置先按后端／账号／根隔离，再比较源书库 UUID 与同数字 ID 的书籍 UUID；相容更新复用身份，不兼容替换隔离新身份。切回已知位置恢复最后有效缓存，不隐式验证源。元数据页展示当前库、数量和同步时间；已读栏目页从导入列出布尔栏目并显式分页，保存设置校验当前身份与导入代次。未配置或栏目改名／删除／类型变化显示配置问题；空／否仅在有效配置下为未读，不维护本地覆盖，不提供写回。

六页最小入口与资源化文案已接入。历史步骤 04 的完成任务仍保留原状态；任务完成提示使用“源加载任务已完成；完整导入结果见当前书库页”，只有实际有效导入才显示“Calibre 元数据已完整导入并激活书库”，避免升级后将历史快照误报为完整导入。

### 必要验证

实际执行：

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:assembleRelease :app:lintRelease
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.metadata.CalibreSnapshotParserTest,io.github.chenxiex.calibrecloud.metadata.MetadataRepositoryTest,io.github.chenxiex.calibrecloud.tasks.LocalSnapshotTaskHandlerTest,io.github.chenxiex.calibrecloud.tasks.OneDriveCandidateTaskHandlerTest,io.github.chenxiex.calibrecloud.tasks.TaskSchemaMigrationTest,io.github.chenxiex.calibrecloud.tasks.DurableTaskQueueTest,io.github.chenxiex.calibrecloud.state.ApplicationStateRepositoryTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

完整实现最终基线为 `BUILD SUCCESSFUL`（51s），JVM **79 tests、0 failures、0 errors、0 skipped**，debug lint **0 errors、9 warnings、1 hint**。修正升级完成文案后实际追加 `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`（43s）与 `:app:testDebugUnitTest`，结果仍成功、同样 79 项 JVM／lint 数量；release 追加检查 `:app:assembleRelease :app:lintRelease` 为 `BUILD SUCCESSFUL`（48s），lint **0 errors、9 warnings、1 hint**；产物为未签名正式包、debuggable=false，未安装或使用正式应用数据。没有改动依赖版本或锁文件。release 日志 `/tmp/calibre-step05-release-build.log`；debug 日志为 `/tmp/calibre-step05-build-complete.log`、`/tmp/calibre-step05-resource-final-build.log`、`/tmp/calibre-step05-resource-final-unit.log`。

平台结果为 **OK (60 tests)**（127.27s），日志 `/tmp/calibre-step05-device-tests.log`。覆盖实际仓库样本（包括 FTS 结构）解析及源字节不变，动态异名栏目与多值关系、空值／否、格式大小和危险路径，损坏／不兼容拒绝、SQLite trigger 注入的发布事务失败、旧代次保留、中断代次回收、重启恢复、切回与 UUID 替换隔离、栏目失效／旧界面配置拒绝，两后端获取后的同一路径导入，以及既有队列协议回归。v1→v3 和 v2→v3 迁移在真实 SQLite 上验证清单、配置、任务／依赖／检查点／序号保留。

有界审阅发现发布与任务结束的竞态。先新增 `publicationCompletesTaskAtomicallyBeforeLaterControl` 并在 PA6 定向执行，稳定复现 **1 test、1 failure**：导入已发布后暂停仍被接受，日志 `/tmp/calibre-step05-publication-regression-device.log`。修复将发布与任务完成放入同一事务，提交后的暂停／取消被拒绝，协调器只在提交后发布事件；上述 60 项设备测试包含此回归且通过。初轮开发编译中发现的包名被 dispatcher 属性遮蔽、参数顺序及测试重复 import 问题已修正，最终检查不是将这些失败记为通过。

新 fixture 按 Calibre 9.14.0 官方表／栏目布局构造，平台确实执行 Android SQLite；其最小结构不等于经过 Calibre 桌面程序打开的完整自定义栏目书库。本轮首次验证时的仓库原始样本没有自定义栏目，不能据此宣称完整桌面兼容、多栏目实库或代表性规模验收完成；用户随后更新的桌面实库补验见下文。

### 本地 SAF 与最小页面技术检查

设备为 PA6（汉王 Clear6 Turbo），Android 14，1072×1448；ADB `192.168.0.72:41871`。安装前 `apkanalyzer` 核对 debug／test application ID 为独立 `.debug`／`.debug.test`，debuggable=true、minSdk=30；安装均 `Success`，未覆盖正式包。最终 debug APK SHA-256：`5fe337aa879f23923a016987dd806027eb991f0609bffda11c76e0bda9e39407`。60 项平台测试使用此前实现相同、仅完成文案调整前的 APK；最终 APK 另执行以下实际页面检查，不将未重复运行的平台测试写成重新运行。

从仓库样本复制两份完整独立测试书库到本次专用临时目录，再推送到设备 Download 下的 `calibre-step05-device-source/library-a`／`library-b`。B 仅在准备副本时修改书库 UUID，两库均含数字书籍 ID 1；不修改仓库原始样本或用户书库。通过真实系统 SAF 选择器授权 A、显式同步，生产持久任务 `Completed`，私有状态为 1 个绑定、1 个完整导入、1 本书及 1 个格式。

再次通过系统选择器选择 B、显式同步后，两个书库各有独立绑定与完整导入，`metadata_books` 中数字 ID 1 分属两个 LibraryId。切回 A 后立即复用原 LibraryId 与原导入代次，任务总数仍为 2，不等待新源同步。暂时移走本次专用 A 根目录并强行停止／重新启动 debug 应用，当前绑定与原导入代次仍恢复，未创建新任务。此检查保持无线 ADB 的 Wi-Fi 连接，证明源不可用情况下私有状态恢复，不宣称设备已实际断网。

移走整个授权根的显式读取返回现有后端的 `UNSUPPORTED_OPERATION`（提供方未返回可用查询结果），旧导入保留；这不作为准确 `SOURCE_MISSING` 分类通过。恢复根后，仅将其测试 `metadata.db` 临时改名，再显式同步实际返回 `SOURCE_MISSING`，页面显示“所选目录或 metadata.db 不存在”，旧 LibraryId／导入代次不变。恢复测试数据库后点击重试，原失败任务完成，复用 LibraryId 并发布新的导入代次，任务总数仍为 4（3 项完成，根目录异常的历史失败保留）。尚未对目录移除时更细的提供方错误分类作新增承诺。

最终 APK 的当前库页显示完整导入、1 本书／1 个格式及同步时间；栏目页对这个无自定义栏目的样本显示未配置及没有可选布尔栏目，不将其展示为全库未读。页面导航、数据与栏目状态均为静态文字，检查第 5／6 页无 scrollable 应用节点。完成文案修正后另核对任务页的升级兼容提示与当前库页的实际有效导入提示。两份设备源 `metadata.db` 在同步、缺失／恢复及重试后的 SHA-256 均与各自准备后的散列相同；相邻 EPUB／封面未进入本步传输，不宣称书籍下载已完成。

### 首次交付时的待验收安排

以下保留首次交付时的安排；其中后续已完成的项目及当前剩余范围，以本文件末尾“共同真机验收准备与结果”的逐项记录为准。

- 真实个人 OneDrive 书库的完整导入、重新登录后同步、失败保留旧索引与断网启动仍需本步实测。步骤 04 的真实快照取得不自动算作步骤 05 完整导入验收；本轮未读取账号凭据或对真实云端文件操作。
- 用户更新的桌面样本已具备单布尔栏目与空／否／是数据，其自动化补验见下文。桌面程序实际改名／删除／改类型后的界面检查、多个布尔栏目分页，以及文本／枚举／多值文本的桌面实库验证仍待补齐；程序变更的副本与最小 fixture 不替代这些验收。
- 需核对真实同目录不兼容替换、用户墨水屏触控／灰度提示及代表性约 286 本书的本地响应；目前没有对应规模样本，不提供未经测量的性能结论。整个授权根移除时的提示分类另保留核对，不把现有 `UNSUPPORTED_OPERATION` 误写为目录缺失分类通过。
- 文件副本、清理、完整图书馆／搜索／分类、后台执行及真实写回仍分别属于后续步骤／阶段；本步只补 AC01 身份／索引部分与 AC09／AC10 的新增路径，不关闭完整 AC01–AC10。

共同验收时安装最终独立 debug APK，仅选择本地／OneDrive 专用测试副本：本地在第 1 页、OneDrive 在第 4 页显式“验证／同步元数据”，第 5 页核对数量／当前身份／成功时间，第 6 页选择已读布尔栏目并分页。切到另一个同数字 ID 测试库再切回，检查各自统计／栏目隔离；重启与断网查看旧导入不自动入队；在测试副本制造缺失／损坏数据库并同步，检查错误及旧代次保留，恢复后显式重试。仅在测试副本中更改栏目与替换数据库，不操作真实书库。共同验收通过前不提交本步。

设备技术检查结束后，卸载 `.debug.test` 与 `.debug` 均返回 `Success`，包列表无匹配；已删除本次设备专用源副本与 UI dump，未删除用户原有测试库或云端内容。后续共同验收需重新安装最终 debug APK。

交付前 `git diff --check`、本次文档本地链接检查、XML／资源键唯一性检查均通过。计划保持被忽略，未暂存或提交；`local.properties`、日志、APK、真实书库与凭据不加入版本控制。

### 桌面自定义栏目实库自动化补验（2026-10-06）

用户通过 Calibre 桌面程序更新 `assets/calibre-sample/` 后，针对 R09、R13、R22–R25 的导入数据基础补验。当前实库为 **3 本书、3 个 EPUB、1 个自定义布尔栏目**（ID 1，查找名 `#read_status`，显示名“阅读状态”）；书籍 ID 1 缺少布尔值行，ID 2 为是（1），ID 3 为否（0）。数据库 SHA-256：`1d099ad42c8eadf553ca2759726e2adcca8f40dc3892665beeb9409bdb7448a8`。本轮未自行更改用户样本，开始与结束核对全部 12 个源文件的散列；只读 SQLite 完整性检查为 `ok`，3 个 EPUB 的实际文件大小均与 `data.uncompressed_size` 相符。

更新原实库解析测试，解除“只有 1 本书且无栏目”的旧断言，核对 3 本书的身份、中文／日文作者关系、标签、丛书与 2.5 序号、格式大小／定位、动态布尔定义和来源值，并验证完整 JSON 往返与解析前后字节不变。新增 2 项 repository 实库测试：未配置时没有已读推断，显式选择导入发现的布尔栏目后缺行／否为未读、是为已读；删除测试快照后重开私有数据库仍恢复完整缓存与栏目设置。

另一项测试在独立实库副本上程序修改栏目 label 为 `renamed_read`，确认原配置失效且不能解释为全库未读，显式选择新名恢复有效，证明实现不硬编码 `read_status`；另以副本修改类型为不支持的 `composite` 或删除栏目定义，验证配置失效、拒绝无效选择及旧导入代次提交，清空选择后恢复未配置。**这些是实库副本的程序故障注入，不是 Calibre 桌面程序完成改名／删除／改类型的互操作证据**。

实际执行：

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.metadata.CalibreSnapshotParserTest,io.github.chenxiex.calibrecloud.metadata.MetadataRepositoryTest,io.github.chenxiex.calibrecloud.tasks.LocalSnapshotTaskHandlerTest,io.github.chenxiex.calibrecloud.tasks.OneDriveCandidateTaskHandlerTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

构建 **BUILD SUCCESSFUL（4s）**，PA6／Android 14 实际平台执行 **OK (24 tests)**（111.17s）：解析器 6 项、repository 8 项（含新增 2 项实库测试）、本地任务处理器 4 项、OneDrive 任务处理器 6 项。后端任务处理器测试为既有模拟后端回归，不算本轮真实 SAF／云盘同步实测。日志 `/tmp/calibre-step05-real-columns-build.log`、`/tmp/calibre-step05-real-columns-device-tests.log`；12 文件散列清单 `/tmp/calibre-step05-real-columns-source-manifest.json`。本轮只调整测试及就近文档，未修改生产逻辑或依赖，未重跑前轮 JVM／release／lint，不把前轮结果记录为本轮新执行。

安装前用 `apkanalyzer` 核对 `.debug`／`.debug.test` 包标识和 debug 可调试状态，两包安装均 `Success`，未使用正式应用数据。debug APK SHA-256 为 `5e7676924b868a31cb1b1fd046052b80e386b4604ed9624f2abdb60bd4a66db5`；测试 APK SHA-256 为 `e3c58525278863e8f19e55a49dfca06a73b30f7cf7f86481df05be9401f21d4e`。测试结束卸载两包均 `Success`，包列表无匹配；全部 12 个仓库源文件与开始时散列相同。`git diff --check` 与本次修改文档的本地链接检查通过；未暂存、未提交，步骤 06 未开始。

本轮已补齐此前缺少桌面自定义栏目书库而无法执行的**单布尔栏目实库解析／配置／来源状态／私有恢复自动化验收**。当前仅 1 个布尔栏目，仍不能验收多个布尔栏目的界面分页，也没有文本／枚举／多值文本的桌面实库；只有 3 本书，不能宣称约 286 本规模性能通过。真实 OneDrive、桌面程序实际更改栏目后的界面互操作及用户墨水屏交互等共同验收继续保留未完成。

### 共同真机验收准备与结果（2026-10-06）

按用户请求重新安装独立 `.debug` 包供共同验收，`apkanalyzer` 核对包名与 debuggable=true，`adb install -r` 返回 `Success`；已启动应用。设备仍为 PA6／Android 14。验收期间保留调试包，八组操作完成后已卸载（结果见本节末尾）；本轮未安装测试包，未访问正式应用数据。

独立样本位于设备 `Download/calibre-step05-acceptance-20261006/`，对应工作区临时产物 `app/build/verification/calibre-step05-acceptance-20261006/` 与同名 ZIP（均被构建目录规则忽略）。`library-a` 是用户桌面样本原样副本；`library-b` 仅程序修改源库 UUID，用于身份隔离／替换，未同步改相邻 OPF，不作为桌面维护的独立库证据；`columns-paging` 程序增加 3 个布尔栏目至总计 4 个，用于最小界面分页，不作为桌面自定义栏目互操作证据。三份均为 3 本书／3 个 EPUB，SQLite 完整性为 `ok`，38 个文件推送完成，三个设备数据库 SHA-256 与准备副本逐一一致。原始样本 12 文件内容保持不变。

一步步操作与预期见 [步骤 05 真机操作指南](step-05-device-guide.md)。安装与样本准备已完成；用户操作结果按下表逐项记录，OneDrive 完整导入、实际离线、分页及桌面栏目变更等未完成项不自动判为通过。

| 验收组 | 实际结果与证据 | 状态 |
| --- | --- | --- |
| 第 1 组：本地导入、栏目选择与重启 | 用户于 2026-10-06 明确反馈“第一组通过”，确认按指南完成 library-a 本地导入、3 本书／3 个格式、布尔栏目选择及强停重启后状态保持；本项为用户操作验收反馈，未新增 ADB 检查或逐本已读界面验证。 | 通过 |
| 第 2 组：切换书库并切回 | 用户于 2026-10-06 明确反馈“第2组通过”，确认 library-b 导入后标识与 A 不同、栏目配置未继承，切回 library-a 不同步即恢复原标识、成功时间与栏目选择；本项为用户操作验收反馈。 | 通过 |
| 第 3 组：栏目分页与墨水屏操作 | 用户于 2026-10-06 明确反馈“第3组通过”，确认 columns-paging 的 4 个布尔栏目分两页可访问、跨页选择与强停重启后保存、清空选择恢复未配置，以及本组按钮／选中提示的墨水屏可辨认与可操作；本项为用户操作验收反馈，程序追加栏目不作为桌面创建多栏目的证据。 | 通过 |
| 第 4 组：实际断网启动 | 用户于 2026-10-06 明确反馈“第4组通过”，确认选择 library-a 后实际关闭联网、强停并重新启动，旧书库标识、3 本书／3 个格式、成功时间和栏目选择仍恢复，无需等待联网且未自动同步；本项为用户操作验收反馈，覆盖本地书库，云端书库的实际断网恢复仍待第 6 组。 | 通过 |
| 第 5 组：本地数据库缺失与恢复 | 用户于 2026-10-06 明确反馈“第5组通过”，确认专用 library-a 副本暂时改名 metadata.db 后显式同步显示缺失提示，旧标识、数量、成功时间与栏目保留；恢复文件名后重试成功，标识和栏目保持、成功时间更新。本项为用户操作验收反馈，不扩展为整个授权根移除时的错误分类通过。 | 通过 |
| 第 6 组：真实 OneDrive 导入与重新登录 | 用户于 2026-10-06 明确反馈“第6组导入与重新登录通过”，确认个人 OneDrive 专用 library-a 测试目录完整导入、后端与 3 本书／3 个格式统计、栏目选择及强停重启保持，同账号同目录重新登录后再次同步成功、书库标识和栏目保持、成功时间更新。本项为用户操作验收反馈；云端实际断网与缺失恢复未包含在此反馈中。 | 通过（断网／缺失另行记录） |
| 第 6 组：云端实际断网恢复 | 用户于 2026-10-06 明确反馈“第6组云端断网通过”，确认当前为 OneDrive 测试书库时实际关闭联网、强停并重启，旧后端、书库标识、3 本书／3 个格式、成功时间与栏目选择恢复，无需等待联网且未自动同步；本项为用户操作验收反馈。 | 通过 |
| 第 6 组：云端数据库缺失与恢复 | 用户于 2026-10-06 明确反馈“第6组云端缺失恢复通过”，确认个人 OneDrive 专用测试目录暂时改名 metadata.db 后同步显示缺失提示，旧书库标识、3 本书／3 个格式、成功时间与栏目保留；恢复文件名后重试成功，标识和栏目保持、成功时间更新。本项为用户操作验收反馈，第 6 组导入／重新登录、实际断网与缺失恢复各子项均通过。 | 通过 |
| 第 7 组：桌面查找名改名的首次操作 | 用户反馈桌面改栏目后删除 OneDrive 书库目录并在同路径重传；第 4 页同步提示目录或 metadata.db 不存在，第 3 页重新选择后同步成功，第 6 页未配置且可见 read_check，第 5 页标识未检查。应用按账号／drive／root item ID 定位，删除重建会创建新目录对象；该现象与原对象消失、重新选择新位置后隔离配置一致（未读取前后 item ID 实证）。本次确认改名栏目可被导入，不能证明原位置原配置的改名失效，须保留目录、覆盖数据库后补验。 | 首次操作记录；失效补验已通过 |
| 第 7 组：保留云端目录的查找名改名补验 | 用户于 2026-10-06 对补验步骤明确反馈“已通过”：先选择 #read_check，桌面改查找名为 read_check2 后关闭 Calibre，只覆盖现有 OneDrive 测试目录的 metadata.db，不重新选择目录直接同步；确认书库标识保持、原栏目配置显示失效，显式选择 #read_check2 后恢复有效。本项为用户操作验收反馈，补齐原位置原配置的改名失效；前次删除重建目录的操作记录保留。 | 通过 |
| 第 7 组：桌面删除已选栏目 | 用户于 2026-10-06 明确反馈“第7组删除通过”，确认桌面测试副本删除 #read_check2 并关闭 Calibre，只覆盖现有云端测试目录的 metadata.db 后直接同步，书库标识保持、配置显示失效且栏目不再列出；清空选择恢复未配置。本项为用户操作验收反馈；桌面改名与删除均通过，桌面类型变化仍未实测。 | 通过 |
| 第 8 组：同目录不兼容替换 | 用户于 2026-10-06 明确反馈“第8组替换通过”，确认重新选择 A 不同步先恢复旧缓存与栏目，随后显式同步替换副本，3 本书／3 个格式仍正常、书库标识改变且新身份未继承旧栏目配置。本项为用户操作验收反馈；恢复原副本后的身份／栏目复用另待核对。 | 通过（恢复另行记录） |
| 第 8 组：恢复原副本 | 用户于 2026-10-06 明确反馈“第8组恢复通过”，确认恢复 A 的备份后显式同步，找回原 A 的书库标识、3 本书／3 个格式与 #read_status 栏目选择，成功时间更新。本项为用户操作验收反馈。 | 通过 |
| 代表性规模及缺少栏目类型的桌面实库 | 约 286 本书响应、桌面创建多个布尔栏目及文本／枚举／多值文本的实库解析、桌面类型变化未提供对应样本或操作结果。程序 fixture／分页副本不替代这些实库验收。 | 待验收 |

第 8 组准备：设备恢复无线 ADB 后（PA6，连接 `192.168.0.72:38165`），将设备专用 library-a 完整 12 文件备份到忽略目录 `app/build/verification/step05-before-replacement-20261006/library-a/`，核对与原准备 A 逐文件散列相同。A 与 B 的相邻书籍／OPF／封面等文件全部一致，仅 metadata.db 不同，因此只覆盖设备 library-a 内的数据库即可使完整内容等同 B；保留原授权根目录对象。设备更新后的数据库 SHA-256 为 `0043c467ca8a1b9fcc0fd75c5138aa018bce455bb0d479f47c2443f7eed4c2af`，与准备 B 一致；不修改仓库源样本或云端内容。尚未操作应用同步，用户应先选择 A 但不同步，查看已保存的旧身份，再显式同步检查替换后的身份与配置隔离；通过后恢复备份。

用户确认替换通过后，已通过 ADB 将备份 metadata.db 恢复至设备同一 library-a 目录，推送成功；设备数据库 SHA-256 恢复为 `1d099ad42c8eadf553ca2759726e2adcca8f40dc3892665beeb9409bdb7448a8`，与原 A 备份一致。尚未替用户触发应用同步；恢复后的原书库身份及栏目复用等待用户下一次显式同步核对。

八组共同操作均已收到用户通过反馈，本地与真实个人 OneDrive 的导入、重启／实际离线、缺失恢复、栏目分页、桌面改名／删除，以及同目录替换与恢复均按实际子项记录。结束前设备 A 数据库散列再次与原备份一致。按项目约束卸载独立 debug 包返回 `Success`，包列表无匹配（本轮未安装测试包），已删除仅本轮设备专用 `Download/calibre-step05-acceptance-20261006/`，检查目录不存在；工作区忽略目录中的 ZIP 与备份保留以供后续准备样本。未删除用户云端测试目录或个人书库，未操作正式应用。已通过八组不代表缺少样本的实库与规模项目通过；步骤 05 整体验收结论及提交仍待用户明确确认，未提交、未开始步骤 06。

### 步骤 05 提交确认（2026-10-06）

用户在收到八组通过结果、剩余样本补验和设备清理状态后明确要求“提交吧”，据此提交步骤 05 当前实现、测试和文档。代表性规模、桌面其他栏目类型／多布尔栏目及类型变化不据此记为验证通过，仍保留补验；后续文件副本、清理、后台与写回仍未实施。本次提交前只作必要静态与暂存范围检查，沿用前述实际构建／测试证据，未重复运行或声称新增运行。
