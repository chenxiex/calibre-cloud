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
