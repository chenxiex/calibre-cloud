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

实现契约见 [本地后端](../src/main/java/io/github/chenxiex/calibrecloud/storage/local/AGENTS.md) 与 [任务模块](../src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)，操作说明见 [本地入口](../README.md#书库)。没有新增产品决策、权限、Manifest、数据库 schema、依赖或锁文件变更。

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

## 步骤 06：完整书籍副本传输、更新与下载清单（2026-10-06）

对应 R04–R08、R11–R12、R17–R18、R31–R35。本步已实现本地复制／个人 OneDrive 下载的单格式处理器、不可变代次发布、失败保留旧副本、导入后的已下载格式版本检查及第 7／8 页分页入口。处理器统一经过现有存储后端与持久单执行队列，普通恢复／翻页／副本读取不访问源。本节以下保留初次完整重传实现的验证历史；用户要求补齐后，两后端续传的当前实现与验证见末尾“断点续传补齐”记录。

本地比较完整源 SHA-256 与传输字节，OneDrive 使用文件 cTag、可知长度与完整传输流；EPUB 验证必需入口和全条目 CRC，PDF 校验文件头与结束标记，这些是传输完整性证据，不是阅读器渲染验证。完整文件及目录同步后以同一 SQLite 事务发布清单与任务成功，再发缓存事件；读取查询／句柄打开与发布共享门，旧句柄保持旧代次至关闭。导入事务仅按已有最小清单原子入队低优先级单格式源检查，确认变化才追加更新，源缺失留副本，网络／登录失败不改删除状态。

### 自动与设备技术验证

设备为 PA6，Android API 34，物理尺寸 1072 × 1448，density 360；使用独立 debug ID `io.github.chenxiex.calibrecloud.debug` 与测试 ID `io.github.chenxiex.calibrecloud.debug.test`。安装前分别通过 `apkanalyzer manifest application-id` 核对实际 APK；provider 仍由独立 application ID 派生。自动化平台源数据均为私有独立 fixture；下述真实 SAF 探测使用设备专用测试副本，不访问个人书库或正式应用。

| 实际检查 | 结果与证据范围 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` | 初次编译暴露 Android SDK 未公开的目录打开常量，改为公开 `O_RDONLY`；随后 lint 发现 `InputStream.readNBytes` 需 API 33，改成最低 API 30 可用的有界读取。修复后整组成功，最后一轮（含中断恢复修复）`BUILD SUCCESSFUL in 1m 50s`；JVM XML 合计 79 项、0 失败／错误／跳过。 |
| debug lint | 0 errors、11 warnings、1 hint；包含既有依赖更新／URI KTX 建议、既有授权 VM hint，以及新传输空间检查的 `UsableSpace` 建议。使用保守可用空间检查与注入故障，不自动回收用户书籍或更新依赖。 |
| `adb shell am instrument -w -r -e class <FormatCopyTaskHandlerTest,ApplicationStateRepositoryTest,DurableTaskQueueTest,BookFileProviderTest,MetadataRepositoryTest> io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner` | `OK (68 tests)`，359.388 秒。包含新增 19 项副本测试与 49 项既有受影响状态／队列／provider／导入测试。两后端桥接使用生产后端与独立文件／模拟 HTTP Graph 响应，不能替代系统 SAF 或真实个人 Graph 验收。 |
| 新增 2 项符号链接回归测试，先测试后修复 | 修复前定向运行显示 `Tests run: 2, Failures: 2`（暂存根链接可引出写入、books 根链接留下目录外发布痕迹）；fixture 仅链接到同一私有测试目录内的隔离位置。之后为生成路径各组件加入链接拒绝，暂存删除使用不跟随链接的遍历。实际日志位于忽略的 `app/build/verification/step06/symlink-before.txt`。 |

新增副本测试覆盖完整发布与 DB 重开、普通缺失零源访问、格式／书库隔离、损坏／版本变化／空间不足／中途流错误保留旧字节、暂停与取消关闭流并清暂存、恢复重新完整取得源、旧句柄读旧字节且关闭后回收、自动检查只更新已下载格式、用户提升保留版本前提、导入与检查原子持久化、已知长度、网络／登录不误标删除及明确缺失保留副本。符号链接修复后完整副本测试实际复验 `OK (21 tests)`，166.796 秒，日志 `app/build/verification/step06/copies-final.txt`。

### 共同验收准备与尚未完成项

操作与每组预期见 [步骤 06 设备指南](step-06-device-guide.md)。忽略目录 `app/build/verification/step06/` 生成 ZIP、散列清单和独立样本：library-a／library-b／library-pause 均 3 本书、4 个格式，SQLite `integrity_check` 为 `ok`，所有 EPUB 的 ZIP CRC 校验通过。首本 PDF、库 UUID 隔离与 32 MiB EPUB 填充由程序 fixture 生成，不作为 Calibre 桌面新增多格式的互操作证据；更新文件另在 updated-files 中，不作为书库。生成时未修改仓库原样本及步骤 05 原准备副本。

本地真实 SAF 的技术探测见下文；用户共同操作、个人 OneDrive 真实下载、实际断网及运行中控制／版本冲突仍待逐组记录；后台执行留步骤 09，外部阅读器与受控 URI 显示名留第三阶段，移除下载留步骤 08。代表性规模与桌面多格式实库样本不足的部分保持补验。本步尚未用户验收、未提交，不推进步骤 07。

### 最终回归与真实本地 SAF 技术探测

真实进程在 `FORMAT_PUBLISH` 中断后，发现恢复页面展示持久的 Running 状态，却没有可执行按钮。先新增 `restoredDownloadScreenRequeuesInterruptedTaskWithoutReadingSource`，修复前实际定向运行失败（预期 Queued、实际 Running），日志 `app/build/verification/step06/restore-before.txt`。随后在页面私有状态恢复时通过共享执行锁将孤立运行记录恢复为排队，完全不访问源；正在执行时不等待、不重置任务。新增第二项测试验证活跃执行器的 Running 状态不受页面恢复影响。两项最终定向复验 `OK (2 tests)`，55.37 秒，日志 `restore-final.txt`。因此新增 23 项副本测试已分别实测通过（符号链接修复后的完整 21 项，加最后新增的 2 项）；不是声称最终一次运行了 23 项或重新执行全部 68 项。

最后生产代码变更后执行整组 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`，成功（1 分 50 秒）；79 项 JVM 测试通过，debug lint 为 0 errors、11 warnings、1 hint。没有依赖、锁文件或 manifest 变更，未新增 release 检查。交付前 `git diff --check` 及本次文档本地链接检查通过。

独立样本已推送至设备 `Download/calibre-step06-acceptance-20261006/`，共 43 个文件、36,637,275 字节，三份设备 metadata.db 的 SHA-256 与准备副本逐一一致。通过系统 SAF 选择专用 library-a 并显式同步，实际导入 3 本书／4 个格式；第 7 页分别复制首本 EPUB（51,734 字节）、首本 PDF（608 字节）、第二本 EPUB（62,776 字节）。第 8 页两页显示 2 条／1 条，各条完整可读。强停后提取私有清单并通过独立 debug 的 `run-as` 读取三个完整文件，与源副本逐字节及长度核对一致；设备源 A 的全部 13 个文件散列保持不变。第 7／8 页截图显示按钮与内容均在 PA6 屏幕内，无应用滚动节点；用户主观灰度触控验收仍待共同操作。

只在设备专用 A 覆盖新版首本 EPUB 后显式同步，实际入队三个低优先级格式检查及一个更新。进程在更新发布阶段被强停，旧副本仍保留；安装修复后的 debug 包保留测试状态，重开页面显示排队和显式执行入口。执行后新版 EPUB 为 51,908 字节，代次改变且与准备的新版字节完全相同；已下载 PDF 与第二本 EPUB 的代次及字节不变，三条检查与一条更新完成，未下载的第三本书没有新副本。本项为真实本地版本更新及中断恢复技术证据。

随后仅将测试源首本 EPUB 改名为 `.epub.absent` 并同步，清单显示源已确认不可用、保留旧副本。提取私有状态并实际读取三个文件，确认首本 EPUB 仍为同一代次、51,908 字节且与更新前旧完整副本一致，其他两条不变。恢复原文件名后再次同步，三条源状态均为 available，文件代次与大小不变。最后将首本源 EPUB 恢复到初始准备版，逐一核对设备 A 的 13 个源文件散列全部与初始 fixture 一致；未再宣称该回退已经同步为私有副本。SQLite 状态、截图、逐字节核对用文件与测试日志只保存在忽略的 `app/build/verification/step06/`，不加入版本控制。

技术探测结束卸载 `.debug.test` 与 `.debug` 均返回 `Success`，包列表无匹配，探测应用私有数据已清除；未访问正式应用。随后为下一轮共同验收重新安装最终独立 debug APK（`Success`）并启动第 1 页，当前为全新未选书库状态，未安装测试包。专用步骤 06 设备书库保留且 A 已恢复初始内容，供指南第 1 组开始；这一新共同验收会话结束后须卸载 debug 包并清理仅本轮专用设备目录。

当前共同验收尚未收到用户通过反馈；真实个人 OneDrive 下载、实际离线、运行中暂停／取消／网络或版本冲突及墨水屏共同操作仍按指南逐项待验收。平台故障注入和本地技术探测不替代这些结果。步骤 06 已实现但未暂存、未提交，待共同验收及用户确认后提交，不开始步骤 07。

### 验收分工调整

用户明确要求本地可自动操作的项目通过 ADB 验收，人工仅保留较高风险的个人 OneDrive 操作、会中断无线 ADB 的实际断网，以及截图无法确认的动画／墨水屏体验。按此调整指南，不再要求用户重复本地既有技术实测；此前真实 SAF 下载、格式隔离、两页清单、版本更新、源缺失／恢复和发布中断恢复证据可用于这些客观子项的验收。尚未覆盖的本地书库切换与运行中控制继续由 agent 补验；无法自动确认的体验不据此通过，用户整体确认和提交仍在验收完成后进行。

### 本地 ADB 验收补验

在已重新安装的独立 debug 包中，通过系统选择器授权 A 并同步，再依次复制首本 EPUB、首本 PDF、第二本 EPUB。第 8 页第一页 2 条、第二页 1 条，末页下一页禁用；强停重启后只查看清单，仍显示完整可读。提取 `local-adb-a.db` 与 `local-adb-a-reopened.db`，核对三条记录的代次完全一致、持久任务总数不增加，再通过 `run-as` 读取三个私有文件并与 A 源文件逐字节比对成功。第 1 组客观功能项据此自动验收通过，动画／墨水屏主观体验保持待人工确认。

| 指南项目 | 当前结论 | 证据／剩余范围 |
| --- | --- | --- |
| 第 1 组本地复制、格式隔离、分页和重启 | ADB 通过 | 本节再次实际操作及副本字节比对；动画体验另行人工确认 |
| 第 2 组实际离线 | 待人工 | 无线 ADB 断网后需人工恢复连接 |
| 第 2 组书库隔离 | ADB 通过 | B 初始空清单，复制后仅 B 的 1 条；切回 A 不同步恢复原 3 条 |
| 第 3 组本地同步更新 | ADB 通过 | 上节真实 SAF 已下载格式更新及代次／字节比对 |
| 第 4 组本地源缺失／恢复 | ADB 通过 | 上节真实 SAF 缺失状态及旧副本字节、恢复 available 核对 |
| 第 5 组本地暂停／继续、取消 | ADB 通过 | 捕获真实 SAF 运行中暂停与取消，继续后完整发布，取消保留旧字节 |
| 第 5 组进程中断恢复 | ADB 通过 | 上节真实发布阶段强停、修复后排队、显式执行及新旧代次核对 |
| 第 6 组真实个人 OneDrive 各子项 | 待人工 | 用户个人测试目录及实际联网操作 |
| 动画／墨水屏体验 | 待人工 | 静态截图不作为通过证据 |

ADB 通过系统选择器切到 B 并同步，第 8 页清单为空；复制 B 首本 EPUB 后仅显示一条完整副本。提取 `local-adb-b.db` 核对 A／B 的 LibraryId 不同、A 的 3 条记录仍保留、B 只有 1 条 EPUB（51,734 字节）。再通过系统选择器切回 A，不点击同步直接查看第 8 页，恢复原三条。`local-adb-a-returned.db` 与最初 A 的清单逐列相同，切回前后任务总数不增加，书库隔离客观子项通过。

本地 `library-pause` 的首本 EPUB 为 33,606,318 字节。首次紧接提交的坐标点击没有捕获控制，因此未记为暂停通过；确认页面显示 Running／format_transfer 后点击实际“暂停”，页面显示暂停，持久任务状态为 paused／format_transfer，同库完整副本记录为 0（`local-adb-paused.db`），本次确实覆盖运行中暂停。随后通过页面“继续”启动重新完整传输，完成及取消结果另以下述实际记录为准。

暂停后通过“继续”完成任务，持久状态为 finished／completed，首本 EPUB 完整发布，33,606,318 字节与大文件测试源逐字节一致（`local-adb-resumed.db`、`local-adb-large.book`）。再次提交同格式，实际点击“取消任务”后 finished／cancelled，旧代次与字节不变，checkpoint 清空、对应暂存目录不存在。为明确覆盖运行中而非仅排队取消，又提交一次，先捕获页面 Running 与私有记录 running／format_transfer（`local-adb-before-cancel.db`），再通过实际按钮取消；`local-adb-cancelled.db` 的全部副本清单与取消前逐列相同，读取大 EPUB 与完整旧字节一致，任务暂存目录不存在。运行中暂停／继续及取消子项据此通过；真实发布阶段进程中断恢复沿用前述已完成证据，无需人工重复。

本轮 A、B、library-pause 共 39 个源文件的设备 SHA-256 均与初始准备内容一致。本轮未修改生产代码、未新增测试或重复构建；仅进行真实本地 ADB 操作、数据核对和验收文档调整，文档链接检查与 `git diff --check` 通过。应用仍处于同一未结束的共同验收会话，保留独立 debug 包和专用测试目录用于剩余人工项；未安装测试包。共同验收结束后按原约束卸载并清理，当前未提交。

完成本地补验后已通过系统选择器切回 A，不同步恢复其原三条完整副本。当前停在第 8 页第一页（2 条，第二页另 1 条），`local-adb-ready-for-manual.db` 再次核对 A 为 EPUB 51,734／PDF 608／第二本 EPUB 62,776 字节，可直接开始人工实际断网及动画／墨水屏体验项。

### 人工验收暂停与续传补齐

用户确认人工第 1 项“本地实际断网重启”已验证完成，据此记录通过（用户实际操作反馈）；随后明确中止后续人工验收并要求补齐两个后端的断点续传。本地离线项不要求重复，个人 OneDrive 和动画体验其余项保留待验收。设备无线 ADB 已恢复连接。卸载独立 `.debug` 包返回 `Success`，包列表无匹配（测试包未安装）；删除仅本轮专用 `Download/calibre-step06-acceptance-20261006/`，检查目录不存在。未触及正式应用或用户云端目录，工作区忽略的 ZIP／测试产物保留。续传开发后的必要独立平台测试会另行安装测试包并于结束卸载，不重新开启人工验收。

### 断点续传补齐

按 R18 与用户本次指示补齐步骤 06 的两后端续传，不将其留到后续阶段。本地使用只读 SAF 文件描述符直接 `lseek` 定位，无法 seek 的提供方明确返回不可续传；完整源 SHA-256 仍用于版本核验。OneDrive 每次恢复重新解析账号／根范围、源 cTag 与大小，重新取得下载 URL，仅在实际内容请求上发送 `Range` 与 identity 编码；校验 206、Content-Range 的起点／终点／总长及实际响应长度，200／416 关闭响应并完整重传。恢复记录不保存下载 URL 或令牌。流读取的普通网络 I/O 转为可重试网络失败，明确截断／超量保留损坏分类；本地写盘失败仍是本地 I/O。

任务暂存采用私有 `.part` 与 `.resume` 证据：先 fsync 已写文件，再原子保存版本、offset、SHA-256、可空总长及完整验证标记并同步目录。块边界检查控制，进度与持久证据节流至约 2 秒，暂停或可重试中断在关闭流时记录实际 fsync 的前缀。恢复校验前缀散列，仅接续同一版本的确认字节，并截断未确认尾部；断点损坏、不支持范围读取或非固定任务的版本变化重新传输，固定版本前提冲突拒绝，不混合源版本。完整验证完成而发布中断时复核源后直接恢复发布，不再取得完整传输流。取消仍清理暂存，普通读取仍只能取得已发布代次。

首次新增 11 项续传测试后实际执行完整副本类 34 项，299.203 秒，33 项通过、1 项失败；失败是旧流中断测试仍断言暂存目录为空。将该断言更新为保留 64 字节和 checkpoint，同时保留旧完整副本、字节及流关闭检查，符合本次续传要求；日志 `app/build/verification/step06/resume-copies.txt`。另新增恢复前断网／授权失效及范围请求断网的两项回归，在保留旧 `retainTransfer=false` 条件时定向实测 `Tests run: 2, Failures: 2`，1.896 秒，确认丢失已有断点（`resume-retention-before.txt`）；随后恢复修复，只有明确版本冲突／损坏／缺失或取消才作废断点，网络／授权失败保留旧恢复证据。

新增 6 项后端 JVM 用例覆盖本地根范围／直接定位、OneDrive 正确范围及凭据边界、200／416 回退、版本／长度冲突及短／多响应；新增 3 项流适配器用例验证网络重试与结构化原因保留。副本平台测试现为 36 项，其中本轮新增 13 项覆盖非零断点、进程恢复、前缀／证据篡改、尾部截断、两后端版本／长度、范围不支持回退、固定版本冲突、网络恢复、取消清理及完整文件直接恢复发布。最终实际执行结果和设备清理见下文。

最终生产代码和资源变更后实际执行 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`，`BUILD SUCCESSFUL in 2m 19s`（日志 `resume-complete-build.log`）；JVM XML 合计 **88 项、0 失败／错误／跳过**，lint **0 errors、11 warnings、1 hint**。随后在 PA6／API 34 执行完整 `FormatCopyTaskHandlerTest` 与 `LocalSourceBackendDeviceTest#realSafRangeMatchesFullSourceSuffix`，**OK (37 tests)**，333.279 秒（`resume-final-device.txt`）：36 项副本测试全部通过，包含修复后的两项断点保留回归；另 1 项真实 SAF 测试通过系统选择器授权本轮独立 metadata.db 副本，实际从 4096 偏移读取并与完整流后缀逐字节一致，前后完整源版本相同。只运行这一无需撤销授权的新增方法，没有运行同类需其它样本并撤销授权的旧方法。生产 OneDrive 范围响应使用真实 OkHttp 的模拟 HTTP 回归，仍不冒充个人云端实际续传验收。

技术测试用独立 `.debug`／`.debug.test` 包，安装前实际 APK ID 已核对。SAF 专用 `Download/calibre-step06-resume-probe-20261006/metadata.db` 测试结束 SHA-256 与准备副本一致（`2a84bdeae960a0d8af1be06825f8739876dadf9c79360fa8cb6d94dfa35f12b1`）。结束卸载两包均 `Success`，包列表无匹配；删除本轮 resume-probe 目录与 UI dump，确认该目录及原人工验收目录均不存在，恢复没有测试安装／测试数据的设备环境。工作区构建日志、APK、ZIP 和探测产物仍在忽略目录；未访问正式应用或个人云端目录。

续传实现、用户文案和就近开发约束已同步，文档本地链接检查与 `git diff --check` 通过；未修改依赖、锁文件或规格中的产品决策。步骤 06 已补齐两后端续传并完成本轮技术验证，本地实际断网重启保留用户通过结论；其余人工验收继续按用户要求暂停，真实 OneDrive 新续传行为及动画／墨水屏体验仍待恢复验收。未暂存、未提交，不推进步骤 07。

### 续传版本人工验收重新准备

用户要求恢复人工验收准备。按此前完整技术验证的最终 APK 重新安装独立 debug 包；`apkanalyzer` 核对 application ID 为 `io.github.chenxiex.calibrecloud.debug`、debuggable=true，安装 `Success`。本轮未安装测试包；包列表只有 debug。APK SHA-256 为 `df04815de1706c1025b66595794f4128003a16b21adba5e4b2aeb9fee566c27b`。设备无线 ADB 为 PA6／API 34，当前连接 `192.168.0.72:41647`。无需重新构建，沿用上节最终整组构建和 37 项真机验证证据，不声称本轮又执行这些测试。

将原初始样本推送至设备 `Download/calibre-step06-acceptance-20261006/`，43 文件、36,637,275 字节；逐一读取设备文件散列，与工作区准备副本全部一致。应用全新未登录，已导航到第 2 页；实际显示“OneDrive 配置有效，尚未登录”及登录按钮。未代用户登录、上传或更改任何云端文件。云端 A／pause 的上传或恢复由用户按新指南完成；本地实际断网重启已有通过结论，不重复。

[人工指南](step-06-device-guide.md) 已改成续传版本的七组人工操作：云端准备／登录、下载／分页／重启、云端实际离线、更新、缺失恢复、续传／控制／版本变化及动画体验。暂停／强停／断网后先取得非零有效断点，再采集继续时的持久进度与最终字节，不把“继续后完成”当作实际续传证据；版本变化子项先暂停取得断点再覆盖源，确保覆盖不混合旧片段的恢复路径。服务端不支持范围访问及固定版本冲突的注入回归不要求人工重复。

准备只读 ADB 采集辅助 `app/build/verification/step06/capture-resume.py`（忽略产物），支持指定 task UUID 与短间隔观察。只读取独立 debug 私有队列与断点，输出状态、进度、offset、前缀散列及版本指纹，不输出账号、源路径、凭据或 URL；不访问 Graph、不写设备文件。实际执行 `--help` 及全新应用的单次采集，得到 `no_format_task`，表明当前没有残留下载任务；这只是采集准备检查，不是续传验收。继续时将观察初始持久进度是否为保存的非零 offset；据已验证处理器与后端代码，范围成功返回才能保持该 offset，完整重传回退会归零。人工操作与对应 ADB 证据共同记入后续结果。

文档本地链接检查与 `git diff --check` 通过。当前仅重新准备环境，没有新增人工通过结论，未暂存、未提交；共同会话期间保留 debug 与专用目录，结束后仍须卸载并清理。

### 验收简化：接口回归与真实云端只读检测

用户指出人工断网重连和重复端到端操作成本过高，要求利用可插拔后端边界，并允许在其准备的专用测试环境中由 agent 进行只读上游检测。据此替换上一节七组人工流程：人工仅准备 `library-a`、自行登录／选择目录和确认动画／墨水屏体验；不再要求上传大文件、计时暂停、强停、覆盖／改名云文件或反复断网重连。此次调整属于验证方法，不改变 R04–R08、R11–R12、R17–R18、R31–R35 的产品行为及验收要求。

既有 88 项 JVM 与 37 项平台测试结果沿用，不声称本轮重新执行。接口回归覆盖实际非零偏移续传、范围不支持回退、版本变化拒绝／重传、流中断与网络／授权预检失败保留断点、暂停／取消及持久化恢复；真实 SAF 范围读取与用户已确认的本地实际断网重启证据继续有效。生产 OneDrive 的模拟 HTTP 测试验证请求／响应处理，不能据此确认真实服务端 Range 行为。

后续最小真实云端检测由 agent 在用户完成环境准备后自动执行，使用设备内现有授权与生产 `OneDriveSourceBackend`／`BackendFormatSource`，不导出凭据：

1. 对专用首本 EPUB 取得版本与大小，完整读取并与本地准备样本的长度／散列比对。
2. 在源不变时从明确非零偏移（例如 4096）调用生产 `openRange`，读取至结束，逐字节对照完整源后缀；再次取得版本确认一致。若返回不支持／回退，记录实际情况，不能以完整读取成功记为 Range 通过。
3. 在测试侧包装真实读取流，取得已持久化非零前缀后可控中断，再通过生产处理器和范围读取恢复，校验接续偏移及完整发布字节；包装仅在测试中注入，不改变生产行为、不关闭设备 Wi-Fi、不修改云文件。这样补齐真实后端与持久化恢复的集成连接，而故障类型组合继续由确定性接口测试覆盖。
4. 客观清单／分页／重启检查由 ADB 操作独立 debug 应用并读取私有状态完成。云端只读限定源文件操作，应用自身任务、暂存与副本仍可正常写入。只记录脱敏状态、偏移、长度与散列，不记录令牌、账号或下载链接。

这些真实云端检查仍待执行；当前只完成测试覆盖审查与指南／计划调整，没有新增云端通过结论，也未实现或运行本节专用真实云探针。实际网络设备问题仅在发现具体差异时追加定向实测，不能把注入测试称为物理断网实测。人工体验仍待确认，步骤 06 未暂存、未提交，不推进步骤 07。

### 真实 OneDrive 只读自动验收

用户反馈环境已准备好后，实际连接 PA6 的无线 ADB（`192.168.0.72:41647`）。独立 debug 已登录并选择 `library-a`，尚未导入；先核对私有浏览结果与所选根匹配，再通过第 4 页的生产只读同步入口取得完整元数据，导入成功。源访问均限于用户所选测试目录，没有上传、覆盖、改名、删除或写回云端文件，没有关闭设备网络。

新增 [OneDriveReadOnlyAcceptanceTest](../src/androidTest/java/io/github/chenxiex/calibrecloud/tasks/copies/OneDriveReadOnlyAcceptanceTest.kt)，属于显式 opt-in 的真实集成测试：默认跳过，只有 instrumentation 参数 `step06ReadOnly=true` 才访问真实云端；同时检查独立 debug 包名、真实根目录名 `library-a`、当前导入身份及测试文件路径／大小／散列，拒绝其它未结束任务。使用应用容器的生产授权、`BackendFormatSource`、共享持久队列及同一执行锁，不提取授权数据，不改变生产实现。测试侧包装流在 8192 字节处注入一次可重试错误，推进测试时钟以跳过退避等待，后续所有内容和范围读取仍使用真实生产后端。

| 实际执行 | 结果 |
| --- | --- |
| `./gradlew :app:assembleDebugAndroidTest` | `BUILD SUCCESSFUL in 5s`，日志 `app/build/verification/step06/cloud-probe-build.log`；只新增测试代码，未重复完整 JVM／lint／旧平台回归 |
| 核对 AndroidTest APK 并安装 | application ID 为 `io.github.chenxiex.calibrecloud.debug.test`；安装 `Success`，正式应用未操作，debug 应用未重装或清除授权 |
| `adb shell am instrument -w -r -e step06ReadOnly true -e class io.github.chenxiex.calibrecloud.tasks.copies.OneDriveReadOnlyAcceptanceTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner` | **OK (1 test)**，107.187 秒；日志 `app/build/verification/step06/cloud-readonly-device.txt` |

该单项集成测试实际完成以下断言，不是模拟 HTTP 通过结论：首本 EPUB 全量读取为 51,734 字节，SHA-256 为 `ba999028397cc788894686459a59e196299e123d74617ca501218afa456b15b5`，与准备副本一致；4096 偏移调用生产 `openRange` 返回非空有效范围流，后缀逐字节与全量对应部分相同，前后源版本一致。生产后端只有严格校验过的 206／Content-Range 才返回范围流，因此本次真实下载端点的范围读取已通过。

随后新建真实副本任务，在首条真实源流交付 8192 字节后注入 `NO_NETWORK`。任务保留有效 checkpoint 与 `.resume`，落盘前缀为 8192 字节且逐字节正确，未发布未完成副本；重试记录 `openRange` 的偏移列表恰为 `[8192]`，完整流打开次数仍为 1。生产处理器成功发布后的完整私有副本与原云端全量逐字节相同，暂存目录清除，前后源版本一致。ADB 只读监测另捕获 durable offset 8192、恢复初始 progress 8192 及最终完整散列，日志 `cloud-resume-observation.txt`。本项证明真实范围读取与断流恢复的集成路径；断流来自测试注入，不称为物理断网或真实进程被杀实测。错误类型组合及进程恢复继续引用既有平台回归与真实本地证据。

通过生产第 7 页另下载首本 PDF（608 字节）和第二本 EPUB（62,776 字节）。集成测试完成后提取私有完整清单，三条 OneDrive 副本逐字节与准备样本匹配（`cloud-copy-bytes.json`），全部持久任务已结束。平台测试包卸载 `Success`；独立 debug 包保留给本轮最后的人工体验确认。分页／重启检查与最终页面准备结果见后续记录；动画／灰度／残影仍待用户确认。步骤 06 未暂存、未提交，不推进步骤 07。

随后实际强停并重新打开独立 debug 应用，不同步，仅导航至第 8 页。清单第一页为首本 EPUB／PDF 共 2 条，第二页为第二本 EPUB 共 1 条，均显示完整可离线读取；末页下一页按钮的 Compose 可点击父节点为 `enabled=false`。最初采集脚本误查文字子节点的 enabled 而断言失败，随后核对正确的按钮节点确认禁用，不属于应用故障。提取 `cloud-reopened.db` 与强停前 `cloud-complete.db` 比较，全部副本清单逐列一致、任务总数不增加。页面已恢复第 8 页第一页，UI dump 已删除；只保留 debug 与本轮专用测试目录供最后体验确认。文档本地引用与 `git diff --check` 通过。当前剩余人工项只有动画／灰度／残影体验与用户整体验收确认；此前用户通过的本地实际断网重启不重复。

### 步骤 06 人工体验通过与设备清理

用户明确反馈新版指南“第二项验收通过”，据此将第 7／8 页动画、灰度辨识与墨水屏残影体验记录为用户人工验收通过。结合既有本地实际断网重启通过结论、接口／平台回归、真实 SAF 和真实 OneDrive 只读检测，本轮步骤 06 指南验收项全部通过。真实物理断网的 OneDrive 端到端操作按已调整的验证分工不重复，不把注入断流称为物理断网证据。第三阶段外部阅读器、后续步骤能力及步骤 05 已保留的实库／规模补验保持原范围，本轮不自动关闭。

体验确认后通过 ADB 卸载 `io.github.chenxiex.calibrecloud.debug`，返回 `Success`；此前 `.debug.test` 已卸载，再查询 debug 包前缀无匹配。仅删除本轮设备专用 `Download/calibre-step06-acceptance-20261006/`，确认目录不存在，结束共同验收会话并恢复无本轮测试安装／测试数据的设备环境。没有访问正式应用或修改云端文件；云端测试目录由用户自行决定是否保留。工作区忽略的 APK／日志／样本保留作为开发证据。

本次仅记录人工通过及清理，未改生产代码，未重复构建或功能测试；文档链接与 `git diff --check` 通过。步骤 06 本轮验收项已完成，尚未暂存、未提交，步骤 07 尚未开始。

用户随后明确要求提交步骤 06。本步验收结果和设备清理已确认；提交包含副本传输／完整发布、两后端续传、版本更新、下载清单、必要测试及相邻约束／验收记录。临时 `plan.md`、构建产物、探测日志、云端定位与授权数据不纳入提交。

## 步骤 07：独立按需封面（验收通过）

本步对应 R06、R10、R12、R17–R18、R31–R36。新增独立 `CoverRepository`、显式 `CoverService`、单资源封面处理器和第 9 页分页入口；沿用应用容器、持久队列及单执行锁。普通读取只查完整私有 PNG；当前可见一本书的缺失封面由页面显式提交低优先任务，等价未完成请求复用。隐藏页面不预取，失败不自动循环重试；切页／换库用选择代号和可见请求代次拒绝旧结果。

Schema v4 非破坏性新增 `cover_cache`，键包含 LibraryId、源数字 ID、源 UUID，记录指向不可变 UUID 图片代次。处理器只读取导入书籍目录的 `cover.jpg`，封面独立于 books/provider；图片流最多 12 MiB，先取尺寸并拒绝边长超过 32768 或一亿像素，再按二的幂采样、缩放为最多 256×384。封面暂存／完整图片 fsync 后，当前身份、导入代次、书籍 UUID 和控制条件在同一 SQLite 事务复核，并将缓存指针与任务成功一起发布。暂停、取消、失败丢弃本次图片暂存，恢复重新获取，不信任半解码图片；失败保留完整旧图。默认 32 MiB 封面限额只回收封面代次，书籍副本不参与淘汰。读取块之间只查询轻量导入代次，不反复解析完整元数据。

OneDrive 新接口先解析确切图片项目，再请求该项目 `/thumbnails`，选择满足显示尺寸的最小图片（均较小时选择最大），无可用缩略图或 thumbnail 404／410 时回退同一图片的内容。图片下载使用独立无授权 client，授权／限流／网络错误交回队列，不打印或持久化临时下载 URL。Microsoft 官方端点和 Android 采样依据已核对并链接到相邻模块约束；没有请求文件夹或书籍格式预览。

| 实际执行（2026-10-06，PA6／API 34） | 结果与范围 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest --tests io.github.chenxiex.calibrecloud.storage.onedrive.OneDriveSourceBackendTest` | 后端 27 项通过，新增 4 项 HTTP fixture 用例覆盖确切 cover item、尺寸选择、无凭据图片请求、空／无效／404 回退、授权／限流及目录边界；不是实际 Graph 响应 |
| `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`（最终生产代码） | `BUILD SUCCESSFUL in 47s`，日志 `app/build/verification/step07/optimized-build.log`；92 项 JVM，0 失败／错误／跳过；lint 0 errors、12 warnings、1 hint，新增提示为建议使用 Bitmap KTX scale；依赖与锁未改 |
| `CoverTaskHandlerTest` 首轮独立 debug instrumentation | `OK (9 tests)`，101.757 秒，日志 `cover-platform.txt` |
| `CoverTaskHandlerTest,ApplicationStateRepositoryTest,MetadataRepositoryTest` 最终 instrumentation | `OK (28 tests)`，180.521 秒，日志 `final-platform.txt`；包括本次轻量代次查询变更后的完整封面回归、受 schema／发布契约影响的状态与导入路径 |
| `adb shell am instrument -w -r -e step07ReadOnly true -e class io.github.chenxiex.calibrecloud.tasks.covers.CoverReadOnlyAcceptanceTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner`，当前为真实 SAF 专用副本 | `OK (1 test)`，3.353 秒，日志 `saf-readonly.txt`；生产 SAF 读取真实 cover.jpg、解码、原图版本前后相同，显式封面任务原子发布，普通完整缓存读取不新增队列任务 |

首轮构建发现 `OsConstants.O_DIRECTORY` 不属于当前 Android SDK 公共常量，使用既有实现同样的只读目录打开／fsync 修正。随后 lint 在新页面的 `when` 局部变量遭遇 Compose detector／Kotlin UAST 工具崩溃；将状态映射与 bitmap 渲染提取为参数函数后检查恢复通过，未禁用 lint 或升级依赖。失败日志保留在忽略目录，不能记为通过。

封面平台测试使用独立 SQLite、文件目录和图片 fixture，覆盖完整缺失读取零源请求、提交去重与缩放持久化、坏图／编码超限／伪造 40000 像素尺寸／版本变化保旧、网络／授权失败保旧、暂停／重开继续／取消关闭流并不暴露半图、书库与 UUID 隔离、LRU 不删除 books、v3→v4 保留导入／清单／任务、当前封面不抢占且用户下载在两个封面子任务之间执行，以及隐藏页零请求、可见页只请求一本书。注入故障与下载处理器只证明边界和调度协议，不冒充真实网络故障或下载验收。

真实 SAF 源位于本轮新准备的 `Download/calibre-step07-acceptance-20261006/library-a/`，来源为步骤 06 已知 fixture 的独立副本；通过系统选择器授权，生产同步成功后 SQLite 显示 1 个元数据导入、0 个封面记录、只有已完成同步任务，确认不因同步预取或等待整库封面。真实只读探针使用应用容器和共享执行锁，默认跳过，仅显式 `step07ReadOnly=true` 且 debug 包、专用 `library-a` 和已知首本图片路径一致才访问源。用户源书库和正式应用未操作。

真实 OneDrive `/thumbnails` 和图片内容尚未执行：当前独立 debug 包为重新安装，账号需用户登录，不能复用步骤 06 的 Range 证据作为新缩略图端点通过。若实际服务无可用缩略图，后续记录原图回退，真实 thumbnail 成功范围仍保持未证实。墨水屏封面／标题占位、灰度与无动画体验也未由用户确认；最短准备见[步骤 07 共同验收](step-07-device-guide.md)。本步未暂存、未提交，不开始步骤 08。

### 生产分页、重启和共同验收准备

真实 SAF 探针后，通过 ADB 导航第 9 页并逐本翻页，私有 SQLite 显示封面记录／任务总数依次为 `1/2 → 2/3 → 3/4`，书籍副本始终为 0；任务为一次同步和三个可见封面，未预取后续书籍或复制书籍格式。加载时的标题占位与完成后的图片／静态状态均可查询，最后一页显示“封面 3 / 3”“第 9 / 9 页”，书籍与应用的“下一页”实际按钮父节点均 `enabled=false`。截图确认新封面及底部控件在 PA6 页面内可见，不能据此确认动画／残影体验。证据为忽略目录的 `page-one.db`、`page-two.db`、`page-three.db`、`last-page.xml` 与 `last-page.png`。

强停并重新打开 debug 应用，未同步；`reopened.db` 的完整封面记录与强停前逐列相同，任务总数仍为 4。对独立 SAF 副本全部 13 个文件逐个 SHA-256 与准备 fixture 比对，全部一致（`source-before.json`／`source-after.sha256`）。源仅通过后端读取，设备源目录未修改。

用户要求继续后，已将独立 debug 应用停在第 2 页“登录／重新登录”；OneDrive 配置有效，但新安装应用尚未登录。AndroidTest 包已卸载 `Success`，debug 与本轮 SAF 专用副本仅为仍在进行的共同验收暂留，真实 OneDrive 检查需要用户自行登录并选择专用 `library-a`。准备完成后由 agent 重新安装独立测试包执行显式只读探针，人工只确认第 9 页墨水屏体验；本轮共同验收结束后仍须卸载 debug／测试包、删除本轮设备专用目录及 UI dump。本节没有新 OneDrive 或人工通过结论。

### 真实 OneDrive 封面与缩略图只读验收

用户反馈“环境已准备好”后，核对独立 debug 当前选择为 OneDrive、私有完整浏览结果中所选 item 名为专用 `library-a`。该位置尚未导入；由 agent 通过第 4 页生产“验证／同步元数据”入口完成导入，并确认所有既有任务已结束，再安装独立 AndroidTest 包执行显式只读探针。没有重新安装／清除 debug 授权，没有写入、改名或删除云端源。

为单独记录真实图片内容，在测试侧增加原图长度／已知样本 SHA-256 比对，以及 thumbnail 描述尺寸、实际解码尺寸、响应字节数和散列的脱敏证据。仅测试代码改变，生产封面处理器与缓存实现未改；构建 `./gradlew :app:assembleDebugAndroidTest` 成功（`cloud-probe-build.log`／`cloud-probe-rebuild.log`，均 3 秒），未无条件重复此前通过的 92 项 JVM／28 项平台回归。

首轮真实检测在 33.226 秒报告 1 项失败（`cloud-readonly.txt`）：原图内容比对与实际 thumbnail 读取成功，但探针错误要求描述尺寸与实际图片尺寸完全一致，实际为描述 800×800、解码 600×800。依据本次响应及 [Graph 官方尺寸表](https://learn.microsoft.com/en-us/graph/api/driveitem-list-thumbnails?view=graph-rest-1.0)中 large 保持原比例／最长边 800 的规则，移除“填满描述框”的错误假设，分别记录描述与实际尺寸，并核对实际图片处于本次描述范围内。生产解码始终使用实际内容尺寸，不需要为这个测试差异改变生产行为。

最终实际执行 `adb shell am instrument -w -r -e step07ReadOnly true -e class io.github.chenxiex.calibrecloud.tasks.covers.CoverReadOnlyAcceptanceTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner`，**OK (1 test)**，69.894 秒（`app/build/verification/step07/cloud-readonly-final.txt`），完成以下真实上游断言：

- 源为已知首本书目录的确切 `cover.jpg` 图片项目，原图 36,903 字节，SHA-256 `a559f3c85079c5e1389db820df0579035bd5e821f4d29a9f395dbf4f23c58615`，与准备 fixture 一致。
- 生产 `/thumbnails` 与无凭据图片内容请求实际返回可用 thumbnail，描述 800×800，实际解码 600×800、24,657 字节，SHA-256 `b27ef2f8c3648dbbeb2cb5e625bebf9e0753f0ab504eca94e8d4bcc41504a5b9`；不是原图回退，也不是文件夹／书籍格式预览。读取前后原图版本一致。
- 显式 `CoverLoad` 经生产共享协调器完整执行 transfer／publish，任务完成、私有图片可读取且处于 256×384 限制以内；再次普通缓存读取不新增队列任务。

这关闭本步首本真实 Graph thumbnail 端点、实际图片解码与发布的待验范围。没有制造云端异常或证明任意文件均有缩略图；无缩略图、404／410 回退、授权／网络失败仍由既有确定性 HTTP／平台回归覆盖，不计为真实服务失败实测。

集成检测后私有状态显示当前 OneDrive 封面 1 条，既有本地库封面 3 条保留，书籍副本仍为 0，全部持久任务已结束（`cloud-complete.db`）。AndroidTest 包卸载 `Success`；debug 与本轮专用 SAF 副本继续暂留用于最后的人工墨水屏确认，结束共同验收后统一清理。步骤 07 尚未获得人工体验通过，未暂存、未提交，不推进步骤 08。

新增只读探针后实际执行 `./gradlew :app:lintDebug`，`BUILD SUCCESSFUL in 38s`（`cloud-probe-lint.log`）；生产源码未改，此前构建／JVM／平台回归证据继续有效。第 9 页已准备为第一本完整云端封面，SQLite 仍只有该库 1 条封面，未预取第二／第三本，书籍副本仍为 0（`cloud-ui.db`）。设备临时 UI dump 已删除，AndroidTest 已卸载；debug 保留供本轮尚未结束的人工体验确认。文档本地引用与 `git diff --check` 通过。

### 步骤 07 人工验收通过与设备清理

2026-10-07（Asia/Shanghai），用户明确反馈“通过”，确认第 9 页封面／标题占位、灰度辨识、残影及无应用动画体验通过。结合 92 项 JVM、28 项真机平台回归、真实 SAF 原图和真实 OneDrive 图片项目 thumbnails 的只读检测，本步 R06、R10、R12、R17–R18、R31–R36 对应的本轮验收项已完成。第三阶段完整图书馆／文件夹代表封面、步骤 08 清理联动、步骤 09 后台执行及既有实库／规模补验保持原范围。

验收后卸载 `io.github.chenxiex.calibrecloud.debug` 返回 `Success`，此前 `.debug.test` 已卸载；再次查询 debug 包前缀无匹配。仅删除本轮设备专用 `Download/calibre-step07-acceptance-20261006/` 和 `calibre-step07-ui.xml`，检查均不存在。没有操作正式应用或云端源，云端测试目录由用户决定保留或删除，工作区忽略目录内的构建／探测证据保留。本轮共同验收会话结束。

本次只记录用户通过与清理，没有生产代码变化，沿用未受影响的自动检查，不重复构建／功能测试。按临时计划“用户确认通过后 commit”的门槛提交本步；提交不包含 `plan.md`、`local.properties`、构建产物、私有状态数据库或调试日志，不开始步骤 08。

## 步骤 08：精确缓存清理（验收通过）

对应 R03、R12、R26、R31–R35，覆盖 AC01、AC03、AC07 的本阶段清理路径。新增生产 `CacheMaintenance`、第 10 页范围确认和第 8 页单格式／全格式移除确认；提交固定完整书籍身份、格式及书库范围。清理不依赖源后端，所有源访问保持既有只读边界。

Schema v5 非破坏性新增 `cache_cleanup` 日志、任务 `revoked`／候选所属库字段和独立 `library_preferences`；保留源书库 UUID、已读栏目配置与绑定，清除完整元数据后仍可复用完整副本。旧任务的发布、重试和调度检查不可撤销的失效标记；新请求在对应清理范围完成前拒绝，范围外请求可入队。事务先移除查询指针并撤销旧生产者，处理器到共享单执行安全边界后删除字节；删除失败或中断保留持久日志，在下一次恢复／调度前重试。已完成历史任务的成功记录保留，不能把清理描述成失败下载。

当前库副本只按匹配清单及相应任务 checkpoint 的不可变代次回收；未匹配格式的任务、暂存和已重命名但未发布文件均保留。普通读句柄与退休代次沿用共享读取门，已有句柄继续读完整字节，最后关闭才回收。清元数据不回收书籍代次，下载源状态变为尚未确认；保留已读任务及未解决保护证据。清其它库只回收冻结的非当前身份范围，保留当前库和未解决恢复资料。多代元数据以私有所属标记回收；无归属且选择代号永久过期的旧候选输入，只作为无用任务暂存经持久日志回收，不推测书库或触及完整缓存与保护域。

### 本轮已经执行的检查

设备为 PA6／API 34，2026-10-07（Asia/Shanghai）。安装前核对 APK：`io.github.chenxiex.calibrecloud.debug`、debuggable=true；测试包为独立 `.debug.test`。仅准备新的 `Download/calibre-step08-acceptance-20261007/library-a`、`library-b`，来自步骤 06 的已知独立 fixture，不操作正式应用或真实书库。

| 实际执行 | 结果与范围 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` | 当轮生产代码 `BUILD SUCCESSFUL in 41s`，`app/build/verification/step08/scoped-build.log`；92 项 JVM，0 失败／错误／跳过；lint 0 errors、14 warnings、1 hint，其中新增 2 个建议使用 SQLite KTX 的提示；遵循既有原生 SQLite 事务风格，未屏蔽检查或变更依赖／锁 |
| 初轮 `CacheMaintenanceTest,CoverTaskHandlerTest,MetadataRepositoryTest,ApplicationStateRepositoryTest,DurableTaskQueueTest,FormatCopyTaskHandlerTest` instrumentation | **OK (99 tests)**，612.501 秒；`app/build/verification/step08/platform.txt`。包含当时的 10 项清理测试以及受队列、schema、身份与读取影响的回归。后续精确代次回收、无归属输入／多代清理新增项须以对应定向复验补充，不能由本行提前记为通过 |
| 专用源准备后逐文件 SHA-256 核对 | 26 个文件全部与工作区准备样本一致；`source-before.json`。这是准备时校验，清理后源完整性须另记 |

首个 instrumentation 请求在安装尚未完成时返回“Unable to find instrumentation info”；待两包安装明确 `Success` 后重新执行，得到上述 99 项通过。该启动失败不记为测试通过，也不属于产品失败。

新增接口／平台用例使用真实 SQLite、私有文件和确定性阶段屏障，覆盖 EPUB／PDF 隔离、冻结格式、多格式／无筛选及仅有未完成任务的格式、现存句柄、发布撤销、安全边界、保护证据、配置、书库切换、符号链接故障、持久恢复与 v4→v5 迁移。它们不访问用户源，不冒充真实 SAF／Graph 或真实进程被杀证据。真实后端探针 `CacheReadOnlyAcceptanceTest` 默认跳过，仅 `step08ReadOnly=true`、独立 debug 包及已知 `library-a`、首书固定格式路径／大小／散列核对通过后执行；在生产共享队列复制两格式、加载封面、精确移除和清元数据，清理前后只读比较源 EPUB、PDF 与 `metadata.db` 的完整字节及版本，不上传或修改源。

当前未完成：候选状态修复后的定向平台复验、真实 OneDrive 清理联动、生产页面两库缓存清理与复用、人工范围说明和墨水屏体验、会话结束设备清理及用户整体确认。共同验收准备见[步骤 08 指南](step-08-device-guide.md)。未暂存、未提交，不推进步骤 09；步骤 05 已保留的实库／规模补验、第三阶段阅读器及完整批量 UI、第四阶段源写回保持原范围。

### 定向复验与 helper 设备兼容

该轮定向 instrumentation：`CacheMaintenanceTest,MetadataRepositoryTest,ApplicationStateRepositoryTest,CoverTaskHandlerTest#versionThreeUpgradePreservesImportedMetadataManifestAndQueuedTasks`，**OK (33 tests)**，59.543 秒，`final-platform.txt`；其中清理 13 项全部通过。补足精确 retire 下 PDF 已重命名但未发布代次保留、metadata 不回收任何 books、书籍删除失败恢复、过期无归属候选输入回收、多代同库元数据回收且保留其它库，以及真实协调器发布前阶段屏障等当轮实现路径。前述 99 项回归继续作为未受该轮差异影响的队列／下载／封面证据。

移除确认文案随后精简为“确认移除：格式范围，副本数，字节数”，以独立“此书全部格式”文本表达无筛选范围，未改变维护规则。实际运行 `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`，**BUILD SUCCESSFUL in 39s**（`ui-build.log`）；仅文案／显示调整不重复整套平台回归。

真实 PA6 上 helper 的 `dumpsys window windows` 查询未包含 `mCurrentFocus`，工具在点击前明确停止（action_may_have_executed=false）。先以离线测试稳定复现，再改为完整 `dumpsys window` 查询；缺失、空值和重复焦点仍被拒绝。修复前 44 项中 1 个错误，修复后 **44 项全部通过**（`helper-before.log`／`helper-after.log`）。没有放宽包名或页面检查，没有绕过元素 helper。实际目录导航中另遇一次点击前 ADB 超时，核对已完成的 Download 页面后仅继续后续步骤；“使用此文件夹”的点击已发生但系统弹窗后置 ID 选错／查询超时，未重试该点击，重新查询确定 AppCompat `com.android.documentsui:id/alertTitle` 的接收方与 `library-a` 范围后才执行新的“允许”动作。随后真实持久授权和生产同步成功，私有状态确认根为本轮 `library-a`、1 个绑定／完整导入、0 个书籍副本。工具细节记录于相邻[helper 验证记录](../../.agents/skills/android-device-verification/references/verification.md)。


### helper 旋转兼容与真实 SAF 只读清理联动

PA6／API 34 的 `dumpsys input` 未提供 `SurfaceOrientation`，helper 原先无法建立设备 profile。新增离线用例先复现：`helper-profile-before.log` 为 50 项中 2 failures、5 errors；修复后 `helper-profile-after.log` **50 项全部通过**。有明确 `SurfaceOrientation` 时仍使用原路径；仅字段缺失时，从 `dumpsys window displays` 默认 display 0 的独立数值 `mRotation` 读取旋转。默认屏、旋转值必须唯一且值为 0–3，缺失、歧义、非法值和配置文本中的旋转不能冒充证据；未放宽包名、页面或设备 profile 检查。随后 PA6 实际 `device-profile` 查询成功。本项属于 helper 兼容与守卫验证，不是应用清理 UI 或墨水屏体验通过结论。

实际执行 `adb shell am instrument -w -r -e step08ReadOnly true -e class io.github.chenxiex.calibrecloud.storage.cache.CacheReadOnlyAcceptanceTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner`，当前为真实 SAF 专用 `library-a`，**OK (1 test)**，88.499 秒；日志 `app/build/verification/step08/saf-readonly.txt`。该测试调用应用容器的生产存储后端、复制／封面服务及共享持久队列／执行锁，属于真实生产服务集成检测，没有操作应用页面，也没有上传、删除或改名源文件。

| 源只读对象 | 本轮真实结果 |
| --- | --- |
| 已知首书 EPUB | 51,734 字节，SHA-256 `ba999028397cc788894686459a59e196299e123d74617ca501218afa456b15b5` |
| 同书 PDF | 608 字节，SHA-256 `634540ec54cc3a5ffd6698d5673134e85f46e4b4cd34549d3ed001190039e888` |
| 根 `metadata.db` | SHA-256 `2a84bdeae960a0d8af1be06825f8739876dadf9c79360fa8cb6d94dfa35f12b1` |

生产服务显式复制两格式并校验完整私有字节；只移除 EPUB 后，该格式清单／普通读取均缺失，PDF 清单、文件代次和完整字节保持。真实封面任务先发布可读取图片，清除当前元数据后 `currentImport()` 和封面普通读取均为空，PDF 仍可离线读取，最小清单源状态为 `UNCONFIRMED`；当前配置与持久已读栏目偏好保留。两轮清理前后对上述三个源对象全量读取并比较字节、版本及 SHA，全部不变。该结果补足本轮 SAF 源完整性与生产服务清理联动；不能据此确认生产页面、两库切换／其它库清理 UI、真实 Graph 或人工体验。

### 新候选状态负例与修复复验

在上述证据之后新增第 14 项清理测试 `otherLibraryCleanupBeforeCandidateValidationIncludesAllOldBindingsAndKeepsCandidateProtection`，验证切到尚无位置／身份的 OneDrive 候选时，仍能清理所有旧绑定缓存并保留当前候选配置与保护资料。先执行该单项稳定复现：**Tests run: 1, Failures: 1**，1.011 秒，`candidate-before.txt`；失败发生在 `requireNotNull(maintenance.previewOtherLibraries())`，候选身份为空时预览错误返回空值。本负例不记为通过。

先前 13 项清理与 33 项定向回归只证明当时已执行范围，不提前覆盖本次第 14 项；后续实际修复与复验见下节。


### 候选状态修复复验与两库 UI 检查进展

修复 `OTHER_LIBRARIES` 预览在当前候选身份为空时的范围判定：不存在已验证当前库时，所有旧绑定均进入其它库范围；执行事务仍核对冻结选择代号，并重新检查当前身份为空／当前身份不属于删除范围，避免确认后配置变化扩大或误用范围。实际执行完整基线 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`，**BUILD SUCCESSFUL in 44s**（`candidate-fixed-build.log`）；92 项 JVM，0 失败；lint 保持 0 errors、14 warnings、1 hint。随后 `CacheMaintenanceTest` instrumentation **OK (14 tests)**，16.641 秒（`candidate-fixed-platform.txt`），包含前节先复现失败的候选状态用例。第 14 项至此具有实际修复通过证据。

此后新增“其它库清理 journal 存在时，当前候选请求范围保持可提交”的定向回归，实际重新打包 AndroidTest **BUILD SUCCESSFUL in 5s**（`candidate-scope-build.log`）。该新范围回归尚未执行 instrumentation，不能以构建成功记为平台测试通过；构建后的对应新差异仍待定向验证。

通过元素 helper 操作生产页面，已在本地 `library-b` 第 7 页点击复制当前格式，并满足“完整副本已发布”的后置条件。`b-copy-complete.db` 核对为 A 库 PDF 608 字节、源尚未确认，以及 B 库 EPUB 51,734 字节、源已确认可用；独立私有文件中两条完整字节仍存在。这是生产 UI 复制与两库私有状态隔离证据，未提前证明切回 A 的复用或其它库清理。`b-to-download` 与 `b-back-local` 各 6 步流程通过；切回 A 的系统选择器授权仍在进行，完整两库 UI 清理流程尚未结束。

无线 ADB 端口由原会话变为 `38105`，旧 serial 点击请求失败并明确 `action_may_have_executed=false`。重新发现连接后，先读取新 serial 的设备 fingerprint，与原 PA6 实测 profile 完全匹配再继续既定流程，没有在失败请求可能已执行的情况下盲目重复点击，也未放宽设备／包名守卫。

当前未完成：新增当前候选 journal 范围的平台回归、完整生产页面两库缓存复用／清理、真实 OneDrive 清理联动、人工范围说明／结果与墨水屏体验、会话结束设备清理及用户整体确认。未暂存、未提交，不推进步骤 09；其它阶段和既有实库／规模补验保持原范围。


### 切回 A 的已清元数据状态复用

`switch-a-picker` 的选择器打开动作已经发生，后置查询因 `foreground_unavailable` 停止，未重复该动作；后续 `inspect` 确認系统选择器实际位于 B 目录。两次 `picker_parent` 均在点击前因 15 秒 UI dump 超时停止，没有点击。主 agent 随后仅在忽略的执行脚本中将单次 dump 查询默认超时延长为 30 秒，继续使用原 helper 的设备锁、双快照、接收包／页面和元素唯一性检查及后置条件，没有改为裸坐标或绕过守卫。之后选择器返回上层成功，`switch-a-authorize-query30` 的三个元素步骤全部完成。

`a-restored.db` 确认当前选择已回到 A，LibraryId 与清理前相同，重选生成新的选择代号；A 的 `metadata_imports` 仍为 0，保留 PDF 原副本代次、608 字节与 `UNCONFIRMED` 状态。通过 `run-as` 对应用私有 PDF 执行 `sha256sum`，得到 `634540ec54cc3a5ffd6698d5673134e85f46e4b4cd34549d3ed001190039e888`，与准备 fixture 及真实 SAF 源读取一致。这证明重新选择已清元数据的书库可以恢复既有身份与完整副本，没有为切换隐式重新导入。`a-to-list` 七步页面流程当时仍在执行，不记为完成；第 8 页复用显示与第 10 页其它库清理继续待后续证据。


### A 的生产下载清单与取消确认

`a-to-list` 的前两步成功，第 3 步在点击前因 30 秒 dump 查询超时停止；重新 `inspect` 确认实际为第 3 页后，只执行 `a-to-list-remaining` 剩余五步，成功到第 8 页，没有重放已完成动作。生产页面实际显示 Quick Start Guide 的 PDF、608 字节、“源尚未确认”及“副本完整，可离线读取”，与 `a-restored.db` 保留清单一致。

`a-removal-cancel.json` 四个元素步骤全部成功：分别打开单 PDF 格式与此书全部格式预览，两次范围均显示 1 个完整副本、608 字节；均点击取消并满足原移除按钮重新出现的后置条件。本项证明生产清单中的精确／全部范围说明与取消路径，不冒充实际副本移除；本轮没有通过这些操作删除 A 的 PDF。

`a-to-cleanup` 两个底部分页步骤当时仍在执行，不记为通过。其它库实际清理、当前元数据预览取消、26 个源文件清理后全量散列复核、新候选 journal 范围回归及第 2 页云端登录准备仍待后续结果；真实 OneDrive、人工体验、设备清理与用户整体确认也未完成。


### 生产其它书库确认与源文件复核

`a-to-cleanup` 两个底部分页步骤完成，到达第 10 页。点击其它书库预览后，`inspect` 核对范围仅包含 B 的 LibraryId `7e7b9391-3fae-4c4f-8784-4ae84e039902`，显示 **1 个书库、1 个完整副本、928,314 字节应用缓存**，范围说明包含保留当前库和保护资料；没有将 A 纳入删除范围。确认清理动作完成，并满足“清理完成”的后置条件。

`other-cleaned.db` 的 SQLite 断言确认：A 当前选择和 PDF 原完整清单保留；B 的 downloaded copies、完整索引、导入和封面记录均为空；`cache_cleanup` 为 0，清理已完成而非仅提交请求。检查 `files/books` 与 `files/metadata`，仅有 A 的原 PDF book 文件，metadata 没有剩余文件。结合前述 B 的实际复制、切回 A 保留副本和生产清单显示，本轮两库生产 UI 隔离／复用／其它库清理客观路径已完成；未受影响的 A PDF 保留，不将清理误记为删除源文件。

实际执行忽略产物中的 `check_sources.py`，`source-after.json` 核对 A／B 两个专用源的全部 **26 个文件** SHA-256，全部与准备 fixture 一致。该检查补足本轮生产 UI 操作后的完整源文件保护证据，不仅依赖单项服务探针或数据库版本不变。

当前仍未完成：第 10 页当前元数据预览／取消的生产 UI 路径、新增当前候选 journal 范围平台回归、第 2 页云端登录准备及真实 OneDrive 清理联动、人工范围／结果与墨水屏体验、会话结束设备清理及用户整体确认。未暂存、未提交，不推进步骤 09。


### 当前元数据取消与新增候选 journal 回归通过

第 10 页当前元数据预览的后置条件核对为 A 的当前身份。`inspect` 实际范围为 **1 个书库、0 个完整副本、0 字节应用缓存**：A 的完整元数据此前已经清空，当前操作不是删除保留的 PDF。取消动作成功返回“清除当前元数据缓存”入口，没有执行第二次清理。至此第 8 页单格式／全格式预览取消和第 10 页当前元数据取消、其它库实际清理的本轮生产 UI 客观路径均已执行。

实际执行新定向方法 `otherLibraryCleanupJournalAllowsCurrentUnvalidatedCandidateSnapshotSubmission`，**OK (1 test)**，20.973 秒（`candidate-scope-platform.txt`）。方法内分别覆盖 LOCAL 和 ONEDRIVE：其它库 journal 存在时，当前尚未验证候选的快照请求仍被接受并保持 `Queued`、`revoked=0`，当前选择与保护资料保留。该证据关闭前节 `candidate-scope-build.log` 只有构建、尚未平台执行的待验项。清理测试本轮累计 **15 项通过**，为此前类级 14 项及本次新增 1 项定向执行；未声称在新增项之后重新运行整个旧平台套件，未受影响证据继续复用。

独立 AndroidTest 包卸载返回 `Success`，debug 应用重新启动，保留用于尚未结束的云端准备／体验会话。第 1 页到第 2 页的登录准备点击仍在执行，`:app:lintDebug`（`final-lint.log`）也尚未完成；这两项暂不记为通过。当前功能验收剩余真实 OneDrive 登录／只读清理探针、最后人工体验、会话结束 debug／设备专用数据清理及用户整体确认；最终 lint 与登录页准备结果待下一节补记。


### 云端共同验收准备与 lint 完成

debug 重新启动后，`login-page` 的第 1 页“下一页”点击成功，后置条件确认已到第 2 页。应用现停在 OneDrive 授权入口等待用户自行登录专用个人账号；没有检查／截图任何账号凭据页面，没有代用户登录或读取授权数据。AndroidTest 包已经卸载，debug 和本轮 `Download/calibre-step08-acceptance-20261007/` 专用 SAF 数据仅为尚未结束的共同验收暂留，结束后统一卸载／清理。

实际执行 `./gradlew :app:lintDebug`，**BUILD SUCCESSFUL in 1m 18s**（`final-lint.log`），关闭前节 lint 尚在运行的状态。当前暂存区 `git diff --cached --stat` 为空，未提交，未开始步骤 09。本地客观清理验收已完成；剩余真实 OneDrive 登录／只读清理探针、最后人工范围／结果与墨水屏体验、会话结束设备清理及用户整体确认。用户按[步骤 08 指南](step-08-device-guide.md)完成最短云端准备后，由 agent 执行必要真实服务检测，不要求重演已完成的本地或故障注入路径。


### 用户完成云端准备与生产同步

用户已自行登录个人 OneDrive 并选择专用 `library-a`。主 agent 核对当前为 OneDrive 候选、尚未验证且完整导入为 0；先通过生产底部分页从第 3 页到第 4 页，一步成功，再点击第 4 页“验证／同步元数据”，后置条件确认源加载完成。`cloud-imported.db` 显示当前 OneDrive 身份已验证、完整导入为 1，全部持久任务均为 `Finished`，具备专用只读探针的无其它未完成任务前置条件。

第 3 页曾尝试选择不存在的同步控件，helper 在任何动作前停止（`action_may_have_executed=false`）；之后依据 `MainActivity` 的实际页面划分到第 4 页执行，没有盲目重试旧选择器。另一次在既有 flow 持有设备锁期间发起 `inspect` 被 `device_busy` 拒绝，没有执行动作，也没有并行设备操作。

独立 AndroidTest 包重新安装返回 `Success`。`CacheReadOnlyAcceptanceTest` 使用显式 `step08ReadOnly=true` 的真实 OneDrive 生产服务探针正在执行，日志为 `cloud-readonly.txt`；尚未得到结束结果，本节不记为通过。剩余真实云端清理联动、最后人工体验、会话结束设备清理及用户整体确认，仍未提交，不开始步骤 09。


### 真实 OneDrive 只读清理联动通过

实际执行 `adb shell am instrument -w -r -e step08ReadOnly true -e class io.github.chenxiex.calibrecloud.storage.cache.CacheReadOnlyAcceptanceTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner`，真实后端为 **ONEDRIVE**，**OK (1 test)**，190.006 秒（`cloud-readonly.txt`）。本项沿用应用容器生产授权、存储后端、复制／封面服务与共享持久队列／执行锁，不导出凭据；属于生产服务只读集成探针，没有操作清理 UI，也没有上传、删除、改名或修改云端源。

真实 EPUB SHA-256 为 `ba999028397cc788894686459a59e196299e123d74617ca501218afa456b15b5`，PDF 为 `634540ec54cc3a5ffd6698d5673134e85f46e4b4cd34549d3ed001190039e888`，根 `metadata.db` 为 `2a84bdeae960a0d8af1be06825f8739876dadf9c79360fa8cb6d94dfa35f12b1`，与已知准备 fixture 及本轮 SAF 结果一致。两格式复制通过生产共享队列完成并验证完整私有字节；真实封面先发布、后随元数据清理变为不可读取。精确删除 EPUB 后 PDF 保留；清元数据后 import／完整索引／封面均不可查询，当前配置、栏目偏好、最小下载清单及 PDF 原文件代次／完整字节保留。清理前后对三个源对象的全量字节和版本复核全部不变。

`cloud-complete.db` 另核对当前云端书库只保留 PDF 608 字节、源状态 `UNCONFIRMED`；import、完整 books 索引及 cover 记录均为 0，全部持久任务为 `Finished`，journal 为 0。这关闭本轮真实 OneDrive 清理联动待验；与本地生产 UI 和平台竞态／恢复证据分别成立，不扩大为任意真实云端故障或源写回验收。

独立 AndroidTest 包卸载返回 `Success`，debug 重新启动。第 8 页移除确认的最后体验页面正在准备，尚未完成，不能提前称为已停在确认范围；生产源码未变，本节仅记录真实探针结果，不重复构建。本步当前剩余最后范围／结果、灰度／残影／动画体验的用户确认、共同会话结束后 debug 与本轮专用源目录清理，以及用户整体验收确认；未暂存、未提交，不推进步骤 09。


### 最后体验页面已准备

`cloud-experience-list` 首次尝试在第 1 步点击前因 30 秒 dump 查询超时停止，`action_may_have_executed=false`、已完成步骤为 0。重新核对原包／页面守卫后，`cloud-experience-list-confirmed` 的七个底部分页步骤全部成功，到达第 8 页。`inspect` 确认单格式移除控件唯一，随后 `cloud-experience-confirmation` 点击成功，后置条件精确匹配“确认移除：PDF，1 个副本，608 字节”。

应用现停在单 PDF 移除确认页，尚未确认删除，保留完整副本供用户检查范围与灰度体验。准备过程未截图、未采集凭据页面。最短顺序见[步骤 08 指南](step-08-device-guide.md)：取消当前单格式预览，查看此书全部格式再取消，到第 10 页分别查看元数据／其它库范围并取消；不要求再次实际清理或复核源文件。当前尚未获得用户共同验收通过，不提交、不开始步骤 09。独立测试包已卸载；最后体验与用户确认结束后，仍须卸载 debug 并删除本轮设备专用测试目录，云端目录由用户自行决定保留或删除。


### 步骤 08 用户验收通过与设备清理

2026-10-07（Asia/Shanghai），用户明确反馈“通过”，确认清理范围／结果说明、灰度辨识、分页、触控、残影及无应用过渡／加载动画体验通过。结合 92 项 JVM、累计 15 项清理平台测试、此前实际执行的 99 项与 33 项相关回归、真实 SAF／OneDrive 只读清理联动及两库生产 UI 范围／复用／清理证据，步骤 08 的 R03、R12、R26、R31–R35 对应本阶段 AC01、AC03、AC07 清理验收路径通过。各检查层次及复用范围以上节实际记录为准，不把只读服务探针记为 UI 操作或真实服务故障。

本次确认不扩大第三阶段真实阅读器／完整批量交互、第四阶段源写回、步骤 09 自动同步／后台执行以及步骤 05 实库／代表性规模补验，原留项保持。用户只需完成本轮指南约定的体验，不需重复已经自动验证的清理／源完整性路径。

用户确认后卸载独立 debug 包返回 `Success`，独立 AndroidTest 包此前已卸载。主 agent 仅删除本轮设备专用 `/sdcard/Download/calibre-step08-acceptance-20261007/`，最终 `pm list packages` 的 debug 前缀查询无输出，确认 debug／测试包均不存在；目录 `test -e` 为 false，输出 `dedicated-step08-directory-absent`。另查 `/data/local/tmp` 的 `calibre-ui-*.xml` 无匹配，helper XML 无残留，设备清理完成。云端测试目录由用户自行决定保留或删除，没有操作正式应用或任何正式书库。此节只记录人工验收与清理完成，未改生产代码，不重复构建。按临时计划的“用户确认通过后 commit”门槛提交本步实现、必要测试及持久文档；提交排除 `plan.md`、`local.properties`、凭据、构建产物、私有状态数据库和调试日志，不开始步骤 09。

## 步骤 09：后台执行、启动同步与阶段联验（2026-10-07）

日期：2026-10-07（Asia/Shanghai）。覆盖 R17–R20、R30–R36；实施前工作区无改动。本步实现、技术检查、真实两后端／系统联验及用户共同体验均已通过，设备收尾完成；最终结论见本节末尾，早期观察和失败记录保留原范围。没有开始第三阶段。准备与体验分工见[步骤 09 指南](step-09-device-guide.md)。

### 实现与验收映射

| 路径 | 实现与证据来源 | 本轮状态 |
| --- | --- | --- |
| R17：后台唤醒和提交／结束竞态 | `BackgroundTasks` 使用一次性 `APPEND_OR_REPLACE` 后继唤醒，提交／控制提交事务结束后才注册；worker、前台测试与恢复共用原持久队列及执行锁。网络／平台重试使用独立链，不阻塞新的用户唤醒。真实 WorkManager 与注入竞态平台回归分别记录。 | 真实 WorkManager、注入竞态、后台传输及实际重启恢复通过；SIGKILL 后立即自动恢复未证实 |
| R18、R20：实际状态和控制 | 第 11 页静态任务列表，显示来源／提升、优先级、阶段、等待与处理器实际控制，任务分页按钮与主页面分页区分；进度更新使用 2 秒闸门，终态立即更新。旧断点不能复用时持久保存重传说明。第 13 页显示通知和系统后台限制，权限显式请求。 | 页面回归、真实任务分页、通知系统记录及用户共同体验通过 |
| R19：启动同步 | SQLite v6 独立保存默认关闭的应用设置，清理保留；应用级协程执行每进程主界面首次打开的提交，worker 仅恢复队列，不触发新启动同步。低优先候选同步与手动高优先请求使用相同键并可提升。 | 设置／迁移、真实 Activity 生命周期、SAF 启动同步及实际重启恢复通过 |
| R31–R34：错误、恢复、日志和隔离 | 原处理器在恢复时核验源版本与暂存；网络条件在派发前读取已知本地状态，永久错误不作无限源重放。debug 日志只含任务内部标识、阶段、状态、字节量和次数，通知不含书名／目录／凭据。 | 原故障证据按范围沿用；真实后台中断／暂停／恢复、内容校验及重启取证通过 |
| AC01–AC03、AC05 及 AC09／AC10 新路径 | 两后端导入、封面／完整副本／更新／清理证据沿用步骤 05–08 未受改动的范围；本步补真实后台、进程／重启、通知与启动生命周期。 | 本阶段已实现路径联验通过；不关闭第三／四阶段或实库／代表性规模补验 |

### 构建、检查与测试修正

执行 `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease`，前一轮稳定构建记录为 `BUILD SUCCESSFUL`（1 分 20 秒），最新完整构建 `build-final.log` 为 `BUILD SUCCESSFUL in 1m 50s`；92 项 JVM 测试，0 失败／错误；debug/release lint 均为 0 错误、14 条既有警告。生命周期测试随后执行 `:app:assembleDebugAndroidTest :app:lintDebug`，当前 `lifecycle-build.log` 为 `BUILD SUCCESSFUL in 25s`。没有更改依赖版本或锁文件。`git diff --check` 通过。日志、报告、APK 与本轮临时产物仅保存在被忽略的 `app/build/verification/step09/` 等构建目录。

前期集成编译因 suspend 函数引用缺少显式返回类型、并行编写时接口尚未到位及 Compose 成员断言的无效 import 失败，均修正后复验；一次 release lint 分析在源码修改期间出现 Kotlin/UAST `ExperimentalDetector` 崩溃，稳定源码后 debug/release lint 正常完成，未禁用检测器或更新工具来跳过检查。新页面原有 Application context lint 提示已改为 `AndroidViewModel`，新增 SharedPreferences 提示已采用 KTX，不新增 lint 警告。

实施时对照 Android 官方[按需初始化](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)、[长任务 worker](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)和[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)核对：自定义配置移除默认 initializer，长任务在 Manifest 与运行时提供服务类型，未授权通知仍须提供前台服务通知。平台对执行机会和配额的约束保持适用，真实设备证据限 API 34，不将其扩大为所有 Android 版本已验证。

实际 debug APK 核对包名 `io.github.chenxiex.calibrecloud.debug`、min/target 30/36、debuggable、独立 `.debug.books` authority；测试 APK 为 `.debug.test`。Manifest 仅补已实现后台所需的网络状态、wake lock、boot、`FOREGROUND_SERVICE`／`FOREGROUND_SERVICE_DATA_SYNC` 和 `POST_NOTIFICATIONS`；`SystemForegroundService` 非导出且声明 `dataSync`，默认 WorkManager initializer 仍移除，通过 Application `Configuration.Provider` 按需初始化。没有周期请求或一般源写接口；release 为未签名 APK，未使用正式签名。

### 真机平台回归与修正后复验

目标 Android 14／API 34 设备实际执行的初轮平台回归为 **101 项，99 项通过、2 项失败**（`platform-tests.log`，527.271 秒），不是一轮全通过。失败分别为真实 WorkManager 首次成功执行的持久次数断言，以及封面页面测试未接入新增自动唤醒的 fixture。前者两条一次性任务实际均成功，但测试错误地期望 `WorkInfo.runAttemptCount == 0`；实际锁定 2.10.5 的 `WorkerWrapper` 在 ENQUEUED→RUNNING 时递增，成功后保留 1，已核对本地实际依赖实现并改为 1。后者等待旧前台驱动超时，已在测试 fixture 中提供显式唤醒驱动，没有为测试改变生产执行行为。

修正后定向复验 **12 项全部通过**（`platform-retest.log`，59.234 秒）：真实 `QueueWorkerPlatformTest`、任务页面 3 项、封面页面 1 项及副本／恢复 7 项。真实 WorkManager 用生产 Application 的按需配置和 worker、连续两次唤醒后两条一次性 work 成功，未创建源任务、启动同步或周期 work；此项空队列证据不替代真实后台传输、通知与进程中断验收。初轮已通过且未受修正影响的其余平台证据按对应范围沿用；没有把两轮计数合并成一次全量通过。

新增网络条件平台回归 **2 项全部通过**（`network-conditions-device.log`，0.65 秒）：真实 SQLite 的本地／OneDrive 绑定和候选上下文使用可控网络可用性边界，离线条件只使云端等待，本地不受影响；启动同步持久保存一条 NETWORK 等待任务，处理器调用数为 0，手动请求复用并提升，网络条件恢复后才派发一次。此处处理器和网络条件均为确定性注入，不是物理断网、真实 Graph 失败或真实云端恢复证据。

### 真机准备、SAF 与 Activity 生命周期证据

目标设备 PA6，Android 14／API 34，无线 ADB；仅安装独立 debug 与测试包，未操作正式应用数据。本轮专用源副本为 `/sdcard/Download/calibre-step09-acceptance-20261007/library-a/`，从已核对的脱敏 fixture 创建 13 个文件，PDF 在准备阶段扩充为 268,436,064 字节并同步该副本数据库的格式大小；原仓库样本和此前源未改。上传前保存全部 13 个源文件 SHA-256，后续源访问只读。该副本用来提供真实传输中的中断观察窗口，不视为代表性大书库或阅读器渲染证据。

真实生产 SAF 手动同步任务 `9a6c679d-01ef-48aa-aaf8-e45242280453` 已完成，持久状态为 `Completed`、来源 `manual_sync`、高优先级。实际 `BackgroundLifecycleAcceptanceTest` 以 `step09ReadOnly=true` 独立执行 **1 项通过**（`lifecycle-device.log`，19.139 秒），只允许上述专用 SAF 目录及独立 debug 包：开启启动同步后首次真实 MainActivity 启动增加恰好一条低优先任务，关闭 Activity 后由生产 worker 完成；首次任务完成后再打开、重建并多次退后台／返回，均不重复提交。先等待首任务完成再重复生命周期，避免未完成请求去重掩盖错误重复。启动同步任务 `df095193-ff46-4b9b-9d52-c3fec37931d6` 的持久状态为 `Completed`、来源 `startup_sync`、低优先级。

本轮读取导出的独立 debug 私有状态快照核对两条上述完成记录；`application_settings` 的启动设置已恢复为关闭，`downloaded_copies` 数量为 0。当前生命周期证据只覆盖真实 Activity 的启动、关闭、重建与后台返回，不能视为实际进程终止、设备重启或运行中传输持续执行的证据。

### 真实后台传输、进程终止与通知的当前观察

首次复制前实际核对设备专用源副本，13 个文件 SHA-256 均与上传前基线一致（`source-integrity.log`：`13/13 SHA-256 unchanged after SAF sync and lifecycle acceptance`），证明此前 SAF 同步与生命周期验收未改源。上述副本数为 0 的私有状态快照也来自首次复制前；不代表后续复制执行中的最新状态。

通过生产第 7 页选择 268,436,064 字节 PDF 并提交复制，UI 流程 `copy-pdf` 成功，持久任务为 `262cad0c-436e-48e2-8e69-079df626167f`，来源 `user_download`、高优先级。按 HOME 后真实 worker 留在后台：源版本核验及传输约 3 分 7 秒后，观察到 23,855,104 字节的非零进度及持久 checkpoint。首次 60 秒观察尚未捕获断点，没有执行终止；延长到 300 秒观察窗口后才捕获并执行，不把前次观察不足记为功能失败。

2026-10-07 13:33:33（Asia/Shanghai），以 `run-as` 对已核对的独立 debug PID `23684` 执行 `kill -9`（`process-kill-long.log` 保存终止前同任务非零进度、checkpoint 和 `restart=false`）。这是真实进程终止，不是暂停、测试处理器重建或强制停止包。其后 45 秒未观察到新应用进程；`jobs-after-kill.log` 显示系统任务 ready 条件成立且 `Num system stops: 1`，本轮没有证明系统即时自动恢复。13:35:27 重新打开 Main 后新 PID `27331` 的生产 worker 恢复同一个 task ID 并保留 checkpoint／`restart=false`，随后再次 HOME，没有新增启动同步；此时仍在源版本核验，尚未得到恢复后的完整副本结果。

初始 `POST_NOTIFICATIONS` 未授权时，真实同步及长复制仍执行，`foreground-service.log` 记录生产 `SystemForegroundService` 的 `isForeground=true`、`foregroundId=9`、dataSync 类型。其后只对独立 debug 包授予通知权限，`notification-static.log` 记录 `queue-execution` 的低重要度 channel 和实际 id 9 通知，通知 `vibrate=null`、`sound=null`、`defaults=0`，flags 包含 `ONLY_ALERT_ONCE`；内容为生产静态后台任务说明。这些是服务／通知的客观系统记录，尚未代表通知视觉、触控、动画或墨水屏体验通过。

新增真实传输验收测试的 `:app:assembleDebugAndroidTest :app:lintDebug` 构建分别为 `transfer-build.log` **9 秒成功**、延长大 fixture 观察窗口后的 `transfer-window-build.log` **6 秒成功**，支持指定 task ID 后的 `transfer-known-build.log` **6 秒成功**。`BackgroundTransferAcceptanceTest` 以 `step09ReadOnly=true` 和上述 `step09TransferTask` 复用同一生产任务执行真实暂停／恢复及流式内容核对；首次 `transfer-device.log` 执行 177.389 秒后失败：真实任务已经达到持久 Paused，但测试错误地要求暂存长度等于已确认前缀，实际 28,442,624 字节暂存包含 23,855,104 字节已确认前缀及崩溃前未确认尾部。处理器先核验前缀、再截断尾部才续传，在前缀核验阶段暂停可以保留尾部；没有因此改动生产行为。测试改为暂存至少包含完整已确认前缀，并仍严格要求暂停期间稳定、无新副本发布以及最终内容大小／SHA-256 完整匹配。修正后的构建 `transfer-retest-build.log` 5 秒成功；指定已暂停任务的 `transfer-device-retest.log` 正在核对持久暂停及恢复，不提前记为通过。主 agent 另以真实私有状态和文件 stat 检查 3 秒，四次长度均为 28,442,624、状态均为 Paused、完整副本数量均为 0（`paused-stability.log`）。指定模式严格核对当前专用 SAF 目录、完整 BookKey／PDF／源定位和其他任务已完成，不另建复制、不调用前台 drain、不注入源内容或修改源数据。

### 任务页定向审查修正

只读审查发现两项 UI 问题：控制被拒绝的错误由普通读取刷新立即清除，以及暂停项被计入待执行序号。先新增对应回归再修正：操作错误独立保存，后台刷新不清除，下一次控制才清除；仅接受的控制请求继续唤醒。暂停项不显示或占用顺序，排队／等待文案明确优先级顺序和条件满足前提。新增 ViewModel 两项使用独立 SQLite fixture 检查拒绝／异常后的刷新与提示，新增页面两项检查暂停序号与独立错误文案，下文补记实际定向真机结果。

新测试首次构建因重复的 `PrivateBookFiles` import 失败；移除重复并使下次控制断言同时等待真实终态与刷新，避免提前关闭测试数据库。随后完整命令 `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease` **45 秒成功**（`build-ui-retest.log`），92 JVM、0 失败／错误，双变体 lint 0 错误、14 既有警告；依赖与锁文件不变。真实传输测试正在运行时没有安装新 APK 或重启测试应用；运行中的验收仍使用原已核对 worker，UI 修正包待该测试结束后安装补验。

### 完整副本、页面回归与重启前状态

修正断点 fixture 后，`BackgroundTransferAcceptanceTest` 指定同一已暂停任务复验 **1 项通过**（`transfer-device-retest.log`，468.292 秒）：实际已确认前缀为 23,855,104 字节，持久暂停及 3 秒不增长通过，生产 worker 恢复后 `restartedTransfer=false`；最终完成私有副本大小 268,436,064 字节，流式 SHA-256 为 `df5090b32a623aa8494529236ec42d5f1a5463850607c10d9d00afb178e71e9d`，与专用源基线完全一致，暂存目录清理。初轮真实 PAUSE 已达到安全边界但 fixture 断言失败，复验从真实已暂停记录开始，不宣称复验再次新发起 PAUSE。持久记录为 `Completed`、checkpoint 清除，完整副本数量为 1。`queue-worker.log` 有最终 `Finished/result=Completed/bytes=268436064` 脱敏日志，关闭此前只观察恢复中阶段的留项；不把这个人为扩充 fixture 的耗时视为代表性性能验收。

随后安装 UI 修正的独立 debug／测试包，`TaskScreenTest` 5 项及 `TaskViewModelTest` 2 项 **全部通过**（`ui-device-retest.log`，129.799 秒），覆盖新增错误保留及暂停项顺序，关闭前节页面回归尚未执行的状态。

有已下载 PDF 时再以真实 MainActivity 和生产 worker 执行生命周期联动，**1 项通过**（`lifecycle-with-copy-device.log`，21.246 秒）：每进程仍只新增一条低优先启动同步 `f5e57689-e4a2-4427-9a04-efb7ae3e55d0` 并完成，设置恢复关闭；导入后实际产生低优先已下载格式检查 `eb704459-551b-49bf-8ea4-9b4d160f36a8`，阶段 `format_check` 尚未完成，旧完整副本保留为 1。这次联动验证同步会交给同一后台队列检查已下载格式，不提前把尚未完成的版本检查记为通过。

重启前再次核对全部 13 个设备源文件 SHA-256 均保持原基线（`source-integrity-after-copy.log`），覆盖实际传输中断、暂停／恢复、完整发布及带缓存同步之后，源文件未改。保存完整副本的内部身份／代次／大小／版本摘要和队列快照用于重连后比对。最初导出时测试进程已退出，没有活跃 PID；主 agent 重开独立 debug 随即 HOME 后，再保存 `before-reboot.json`：debug PID `29362`、boot_count `26`、同一未完成格式检查的持久 `Running` 记录、完整副本 1。没有在重启前成功取得新的前台服务 dump，因此这里不以持久 Running 记录冒称重启瞬间已经观察到活跃传输。

`adb reboot` 命令返回 0 后无线 ADB 断开。旧地址重连返回 `Connection refused`，mDNS 无发现，设备列表仅旧地址 offline；本轮尚无重启后的 boot_count、队列／副本或 worker 日志。实际重启恢复验收保持未完成，需要用户提供无线调试页的新连接地址后继续核对同一任务、完整副本代次、源完整性及后台恢复，不需要用户手工计时或反复断网。

### 重连后的真实重启恢复

用户重新连接无线 ADB 后，主 agent 在打开 Main 之前取证：系统 boot_count 从重启前 26 增为 27，同一个低优先 `downloaded_format_update` 任务 `eb704459-551b-49bf-8ea4-9b4d160f36a8` 已由生产 worker 完成。`queue-worker-after-reboot.log` 记录重启后 PID `4600` 在 13:56:01 开始 `format_check`，13:59:14 得到 `Finished/result=Completed`（193,605 毫秒）。全部原 5 条任务均完成，task ID 集合没有增加；这次是实际重启后系统后台恢复，关闭之前重连取证未完成的留项，不扩大先前 SIGKILL 后 45 秒无即时恢复的结论。

`after-reboot.json` 核对原完整 PDF 的书库／书籍／格式、文件代次、大小和保存版本与 `copy-before-reboot.json` 完全一致，仍只有 1 个副本，没有重复发布。独立 debug 私有 PDF 的 SHA-256 仍为 `df5090b32a623aa8494529236ec42d5f1a5463850607c10d9d00afb178e71e9d`、大小 268,436,064 字节；全部 13 个设备源文件也保持上传前 SHA-256，恢复没有改写源或删除完整副本。取证完成后才打开 Main 准备个人 OneDrive 登录入口。

### OneDrive 生产后台与清理联验

用户完成个人账号登录及专用测试目录选择后，主 agent 从第 4 页生产入口执行“验证／同步元数据”，实际同步任务 `1d5d99c1-3593-4d9b-801a-e43fa762d701` 为高优先级 `manual_sync`、Completed，独立云端身份激活且本地导入保留。随后通过真实设备执行 `adb shell am instrument -w -e step08ReadOnly true -e class io.github.chenxiex.calibrecloud.storage.cache.CacheReadOnlyAcceptanceTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner`，**1 项通过**（`cloud-worker-readonly.log`，279.89 秒）。测试严格限定独立 debug 与专用 `library-a`，调用生产副本／封面服务提交任务并等待真实 WorkManager，不直接 drain，不进行 UI 操作，不注入源响应。

EPUB、PDF 与封面任务分别为 `98307cec-66ca-48e2-b8c1-f0691344cabe`、`c004dbec-750e-44f2-9ddc-c42e48d404d3`、`ffb91c51-2f5c-4ace-ad98-57eb86ff8f6c`，均 Completed。EPUB 为 51,734 字节、SHA-256 `ba999028397cc788894686459a59e196299e123d74617ca501218afa456b15b5`，PDF 为 608 字节、SHA-256 `634540ec54cc3a5ffd6698d5673134e85f46e4b4cd34549d3ed001190039e888`。真实封面完整发布后清除；精确移除 EPUB 保留 PDF，清除当前云端元数据／封面后 PDF 私有内容仍可读、源可用性为未确认，配置和偏好保持。测试前后云端格式与源数据库的字节及版本一致，数据库 SHA-256 为 `2a84bdeae960a0d8af1be06825f8739876dadf9c79360fa8cb6d94dfa35f12b1`，没有上传、删除、改名或写入源。

测试后主 agent 独立比对真实清单（`cloud-isolation.log`）：当前两个完整 PDF 分属不同书库，本地 268,436,064 字节 PDF 的完整身份、文件代次、大小和版本与重启前完全一致，云端 608 字节 PDF 保留。上述清理使云端元数据暂不可用符合既有规则，体验前由生产入口重新同步，不要求用户重复清理联验。

体验前重新从第 4 页生产入口同步成功：高优先级手动任务 `6c768bbb-e249-4b2d-ae08-e10f08dba974` Completed，恢复云端导入，实际派生低优先级已下载格式检查 `384ae127-5c0a-466c-9191-aa6cd62abe58`，也由生产 worker Completed；真实快照 `cloud-refresh-state.json` 为 13 条全部完成、两个书库导入、两个完整 PDF。闭合云端“清元数据 → 重新同步 → 自动检查已有下载”联动，未重复发布副本。本轮只追加真实集成和文档记录，沿用此前稳定源码的完整构建及定向回归，没有把尚未完成的用户体验记为通过。导航曾发生 ADB 查询超时，helper 明确记录未点击，按确认的剩余步骤继续，最终第 4 页同步后置条件成功。

随后导航经过第 9 页，页面按需创建一条新的低优先级封面请求 `6274a149-cb55-45da-9f4f-8df1f2c436b5`，生产 worker 已完成，因此体验准备时实际为 14 条完成任务。这是重新导入后新的可见封面请求，不是清理前旧结果复活。任务分页准备初次按先前 13 条快照设定前置条件，helper 因实际总数 14 拒绝且未点击；按真实节点重新核对后继续，没有为测试改变生产状态。

真实第 11 页使用独立任务翻页按钮，从“任务 1 / 14”连续完成 13 次到“任务 14 / 14”（`task-pagination14` helper 成功）。首项的“上一项任务”可点击父节点为禁用。页面状态为已完成，用户请求／自动任务标签实际可辨识；灰度、残影、动画和触控体验仍交用户确认，元素查询不替代体验。

第 12 页真实后置条件确认“启动应用时自动同步：关闭”，第 13 页确认“系统通知：开启”，然后按页导航回第 11 页（`settings-experience-retry` 完成第 1 步，`settings-experience-remaining2` 完成剩余 3 步）。查询超时均为 `action_may_have_executed=false`，没有重复未知执行结果的点击。第 11–13 页已准备供共同体验；指南仅要求任务翻页、开关／立即同步及通知抽屉体验，不要求重复客观数据验证。上述文档更新后 `git diff --check` 通过。

### 最终共同验收与设备收尾

用户于 2026-10-07 在第 11–13 页体验指南与真实结果交付后明确回复“通过”，确认任务分页、同步设置、通知／后台提示及灰度、动画、残影、触控的共同体验。本步对应 R17–R20、R30–R36 的实现、必要自动检查、真实本地／OneDrive 后台联验及共同验收完成，第二阶段已实现路径完成；AC01–AC03、AC05 及 AC09／AC10 保持上述本阶段证据边界，没有开始第三阶段。

已保存验收后的脱敏任务快照 `accepted-final-state.json`。实际执行 `adb uninstall io.github.chenxiex.calibrecloud.debug.test` 与 `adb uninstall io.github.chenxiex.calibrecloud.debug`，两项均返回 `Success`；随后仅删除核对过真实路径、根目录只含 `library-a` 且含本轮数据库的 `/sdcard/Download/calibre-step09-acceptance-20261007`，检查该目录不存在、两个包已卸载，其他匹配包清单保持不变（`cleanup.json`）。没有操作正式应用、真实书库或云端测试目录，云端目录由用户自行决定保留或删除。原始日志／APK／设备产物保持在忽略的构建目录，`plan.md` 与本地配置不加入提交。

代表性实库／规模、无相应样本的存储卷／提供方、第三阶段阅读器和第四阶段真实源写回保留原未完成范围；本轮没有证明 SIGKILL 后系统即时自动恢复，不扩大为所有 Android 版本或代表性性能通过。历史失败的 fixture 已按本节实际定向复验关闭，不将多轮计数写成一次全量通过。

## 阶段一、二检查与 Q31–Q34 调整（2026-10-07）

对应 R18、R32 及 [Q31–Q34](../../questions.md#九阶段一二检查结论)：登录／目录授权失效改为等待并在重新授权后自动继续，服务端限流显示“等待限流”，本地复制以 SAF 文档大小作为空间预检与进度总量，调度顺序等待只显示“排队”。同时删除未使用的 `SourceSynchronization`、`StartupSyncSetting`，并修正两处真实 SAF 测试的过期预期。

### 构建、JVM、lint 与平台回归

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease` 为 `BUILD SUCCESSFUL`；两变体 lint 为 0 errors，剩余 5 个上游依赖版本 warning。设备 PA6／API 34 上安装独立 `.debug`／`.debug.test` 后执行完整 instrumentation（不带 opt-in 参数），**OK (190 tests)**，8 项真实上游 opt-in 测试按假设跳过（`check-20261007/full-suite.txt`）。

### 真实本地 SAF 验收

专用副本为 `/sdcard/Download/calibre-check-20261007/library-a/`，由仓库示例书库原样推送，12 个文件；开始前保存全部源文件 SHA-256。通过 helper 在系统选择器授权该目录，第 1 页“验证／同步元数据”成功，导入 1 个书库。

- `-e localSaf true` 执行 `LocalSourceBackendDeviceTest#realSafRangeMatchesFullSourceSuffix` 与 `LocalDirectoryAuthorizationDeviceTest`，**OK (2 tests)**。前者新增断言：生产 SAF 后端返回的 `metadata.db` 文档大小等于完整读取的字节数（Q32）；后者最后释放持久授权。
- 授权撤销状态下，第 1 页显示“目录授权已失效，请重新选择。”。第 7 页复制首本 EPUB、经过第 9 页触发可见封面、第 12 页“立即同步元数据”后，副本、封面和手动同步三项任务均为 `Waiting(directory_authorization)`，没有判为失败；第 7 页显示“目录授权已失效，请重新选择。”，第 11 页任务显示“等待条件／等待目录授权”（Q31）。
- 回到第 1 页重新选择同一 `library-a` 并允许后，10 秒内全部自动继续：副本与封面原任务 Completed；原同步任务按候选令牌规则取消，等价的新高优先 `manual_sync` 任务 Completed，随后派生的已下载格式检查 Completed。私有副本 SHA-256 与源 EPUB 一致（`ba999028…b15b5`）。
- `realSafSnapshotSourceReadsCancellationAndRevocation` 首次失败：测试仍要求同步完成后没有书库身份，这是导入接入前的预期。改为断言身份绑定到所选目录后重新执行，**OK (1 test)**（`saf-snapshot.txt`／`saf-snapshot-retest.txt`）。

结束时 12 个源文件 SHA-256 与基线一致。两个包卸载均 `Success`，专用目录已删除，包列表无匹配，`/data/local/tmp` 无 helper XML 残留。

### 界面文案对齐

队列由后台自动执行，等待条件满足后自动继续，因此排队与等待提示不再要求“显式执行”或“重新执行”。按钮“执行排队任务”改为“立即执行”，它仍只请求立即唤醒队列。网络等待提示自动继续；任务已失败于网络不可用时，才提示恢复连接后重试。完整构建、单元测试 92 项与两变体 lint（0 errors，5 个上游 warning）通过。只改文案和状态映射，没有在真机上复验。

### 真实 OneDrive 登录失效与重新登录

重新安装最新 debug 包后，用户自行登录个人测试账号并选择专用云端 `library-a`；主 agent 未查看登录页面。第 4 页“验证／同步元数据”成功，导入 3 本书，书目与既有 `library-a` fixture 一致。

- 用户在 Microsoft 账户的应用权限页删除本应用权限后，第 2 页显示“需要重新登录。”。
- 此时第 7 页下载首本 EPUB、经过第 9 页触发可见封面、第 12 页“立即同步元数据”，三项任务均为 `Waiting(login)`，没有判为失败。第 7 页显示“需要重新登录。”及“立即执行”，第 11 页显示“等待条件／等待登录”（Q31）。
- 用户重新登录后无需其它操作，任务按优先级自动继续：高优先副本原任务 Completed，原同步任务按候选令牌规则取消，等价新同步任务 Completed，低优先封面原任务 Completed，随后派生的已下载格式检查 Completed。私有副本 51,734 字节，SHA-256 与已知 fixture 一致（`ba999028…b15b5`）。

结束后卸载 debug 包返回 `Success`，包列表无匹配，`/data/local/tmp` 无 helper XML 残留。云端测试目录由用户决定保留或删除。

### 尚未完成

- 真实服务端限流无法按需触发，“等待限流”仅由 Graph 429／5xx 映射与队列退避的接口回归覆盖。
- “排队”：本次只删除了额外提示，排队状态的显示代码没有变；示例书库文件太小，无法在真机上稳定构造任务排在执行中任务之后的场景，也没有对应的页面自动测试。
