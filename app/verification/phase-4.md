# 第四阶段验证记录

范围以 [spec.md](../../spec.md) 第四阶段“已读写回”为准（R13–R16、AC04，以及 R18、R26、R27 中的写回部分）。本记录保存实际执行结果；自动检查通过不等于用户共同验收通过，未执行的路径不记为通过。

## 实施前探测（2026-10-10）

写回模型（Q75–Q81）确定前的三项探测。临时探针代码与探针文件均未提交，探针文件已核对后删除；源写入只针对专门的副本或探针文件，没有写入真实书库。

### Calibre 9.14 写入语义

- 方法：在开发容器中用 `/opt/calibre/calibredb set_custom` 对仓库样本 [assets/calibre-sample/](../../assets/calibre-sample/) 的副本把布尔栏目设为“是”和“否”，逐表比较写前／写后的数据库。
- 结果：设为“是”或“否”时，`custom_column_N` 中该书的行被替换为新行（新 id，`value` 为 1 或 0，“否”是显式 0 行）；`sqlite_sequence` 中该表序号加一；`books.last_modified` 设为当前 UTC（`YYYY-MM-DD HH:MM:SS.ffffff+00:00`）；`metadata_dirtied` 插入该书。设为已有的值不修改任何数据。`preferences.last_expired_trash_at` 的变化是 Calibre 打开书库的副作用，不属于写回。`journal_mode` 为 `delete`。
- 触发器：`books_update_trg` 在语句准备阶段需要函数 `title_sort`，未注册时 `UPDATE books` 报错。注册一个被调用即抛错的 `title_sort` 后，以 `INSERT OR REPLACE`、`UPDATE books SET last_modified` 与 `INSERT OR IGNORE INTO metadata_dirtied` 三条语句得到与 Calibre 一致的差异（只差行 id 与时间戳）；`calibredb check_library` 无问题，`calibredb list` 读回正确。
- 样本含 FTS5 虚表 `annotations_fts*`，变更范围比较须跳过虚表、比较其影子表。

### 本地 SAF（PA6，Android 14，系统 ExternalStorageProvider）

- 方法：独立 debug 包在专用探针目录中创建探针文件，执行写入与重命名，结束后核对并删除。
- 结果：打开模式 `rwt`、`wt` 截断写入，`w`、`rw` 不截断；`FileDescriptor.sync()` 成功；文件 flags 含写入、删除、重命名、移动；`renameDocument` 到已存在的名字时改名为 `probe (1).bin`，不替换原文件。因此本地提交不能依赖“重命名覆盖”，采用 Q78 的三步重命名。

### OneDrive 条件上传（PA6 debug 包，`library-a` 根目录）

- 方法：在用户授权的测试书库根目录上传自建探针文件，以不同前提再次上传，核对后删除。
- 结果：`PUT /items/{id}/content` 携带当前 eTag 或 cTag 的 `If-Match` 返回 200 并更新；携带过期值返回 412 且内容不变。`createUploadSession` 携带过期 eTag 返回 412；会话创建后源被改动时，最后一个分片返回 404 且不覆盖。简单上传支持 250 MB 以内。

## 步骤 01：变更列表任务、整轮重试与紧接同步（2026-10-10）

对应 R13（待写入状态推导）、R14（变更列表与合并）、R15（整轮重试的队列部分）、R16（紧接同步）、R17、R18 的队列部分，AC05 的已读任务合并与写后同步。本步只有队列与持久化，没有写回处理器、源推送或界面；规则见[任务约束](../src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md#已读写回任务)。

### 改动

- 删除旧写回协议：`CommitState`、`TaskRecord.commit`、`recordCommit`、`RetryFrom`、阶段 `WRITE_REFETCH`／`WRITE_IMPORT`／`RECOVERY_CHECK`、`METADATA_FETCH`／`METADATA_IMPORT`、等待原因 `RECOVERY`、`relatedWriteKeys`、依赖 `SOURCE_COMMIT_CONFIRMED`／`SAFE_TERMINAL`、`safeTerminal`、`SnapshotFreshness`、`TaskRequest.MetadataSync`、`ReadStatusIntent` 及其分支、文案。`TaskResult.Cancelled` 不再携带提交证据。
- `ReadStatusWrite(libraryId, column, batch)`；schema v14 新增 `read_status_changes`、`queued_tasks.started`／`follow_up_id` 与 `dispatch_next`。
- `submitReadStatus` 合并事务、`started` 标记、`FINISHED` 依赖、`StageOutcome.Complete(followUp)` 与下一个任务选择规则、写回的 `VERSION_CONFLICT` 整轮重试、`pendingReadStatus` 查询、成功同步时隐藏失败（`dismissReadFailures`）、重试恢复待写入。
- 清理：清元数据保留写回；其它书库清理与删除书库撤销写回并删除变更行；保留规则删除任务时删除变更行。

与计划的工程差异：

- 迁移把旧的 `MetadataSync` 与无变更列表的写回行**删除**（连同依赖边及其它任务载荷中对它们的引用），而不是标为取消：类型已删除，旧载荷无法解码。生产中这两类请求没有处理器，不能被提交，因此没有源写入记录丢失。
- 导入成功不删除失败写回的变更行，而是置 `dismissed`，以免手动重试时变更列表为空；重试清除该标记。
- `pendingReadStatus` 增加栏目参数，只返回当前栏目的待写入书籍。
- 再次标记某本书时，从同书库、同栏目已失败或取消的写回中删除该书的行，避免旧任务重试写回旧目标。

### 自动检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

结果 `BUILD SUCCESSFUL`（1m 48s），JVM **153 tests、0 failures、0 errors、0 skipped**；lint 0 error、5 warning（数量与第三阶段相同，未逐条比对）。`git diff --check` 无问题。

### 真机平台测试

设备 PA6（无线调试）。安装前用 `aapt2 dump badging` 核对 debug application ID 为 `io.github.chenxiex.calibrecloud.debug`；用 `adb install -r -t` 安装 debug 与测试 APK，按 runner `AwakeTestRunner` 执行：

```bash
adb shell am instrument -w -r -e class io.github.chenxiex.calibrecloud.tasks.DurableTaskQueueTest,io.github.chenxiex.calibrecloud.tasks.TaskSchemaMigrationTest,io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenanceTest,io.github.chenxiex.calibrecloud.ui.TaskScreenTest,io.github.chenxiex.calibrecloud.ui.TaskViewModelTest,io.github.chenxiex.calibrecloud.tasks.background.QueueConditionsTest,io.github.chenxiex.calibrecloud.tasks.background.QueueBackgroundTest io.github.chenxiex.calibrecloud.debug.test/io.github.chenxiex.calibrecloud.AwakeTestRunner
```

- 第一次执行在 `aRejectedPushRunsWholeRoundsUpToTheRetryLimitAndCannotBeInterrupted` 处挂起，测试进程被手动强停。原因：协调器在阶段推进时把重试计数清零，写回每轮“获取 → 准备 → 推送被拒”后计数回到 0，永远达不到 3 次上限。已改为写回的计数按整轮累计、阶段推进不清零。
- 修正后重跑：**71 tests，1 failure**（73.2 s）。各类：`DurableTaskQueueTest` 28、`CacheMaintenanceTest` 19、`TaskSchemaMigrationTest` 6、`QueueBackgroundTest` 5、`TaskScreenTest` 9、`QueueConditionsTest` 2、`TaskViewModelTest` 2。失败项为 `TaskScreenTest.runningShowsStageAndPercentWithOnlySupportedControlIcons`：测试记录由元数据同步改为格式检查后，断言的标题文字未同步。修正断言后单独重跑 `TaskScreenTest`：**OK (9 tests)**。

新增或改写的平台测试覆盖：任务开始前的点击并入、同一本书以最后一次为准（含持久库重开）；开始后的点击进入新任务并以 `FINISHED` 依赖排在其后；不同栏目进入新任务，原未开始任务仍可并入；合并与开始执行并发 5 轮，每次点击要么在固定的变更列表中、要么进入后续任务，开始后的列表不再变化；无需修改时在准备阶段完成并提交同步；同步可执行时紧接写回执行（越过此前排队的用户请求），等待网络时不阻塞其它任务、记录被清除；推送被拒时整轮重跑共 4 轮后失败，推送阶段拒绝暂停／取消，重开后手动重试再跑 4 轮；`pendingReadStatus` 在未开始、执行中、已推送待同步、单书失效、同步结束（失败）、成功同步后隐藏失败、写回失败、再次标记覆盖并移出旧失败任务、取消、重试恢复待写入、重开各状态下的返回值，以及其它栏目不返回；保留规则删除任务时删除变更行；直接 `submit` 写回被拒；旧书库写回不阻塞新书库；v13 → v14 迁移删除旧 `metadata_sync` 与旧格式写回行、依赖边及载荷引用，迁移后可提交写回、外键检查无误；清元数据保留写回及其变更列表，其它书库清理、无当前书库的清理、清理 journal 期间与删除当前书库均撤销写回并删除变更行。

`LocalLibrarySyncTest`、`AuthorizationResumeTest`、`FormatCopyTaskHandlerTest`、`CoverTaskHandlerTest` 只有 `TaskResult.Cancelled` 的机械替换，本步只编译、未在真机运行。

测试后已卸载 debug 与测试包，设备上只保留正式应用（未触碰）。

### 未完成

本步没有源访问，不涉及 AC04 的真实写入、冲突或 Calibre 一致性；这些在步骤 02–07 验证。用户共同验收尚未进行。

## 步骤 02：暂存数据库生成与变更范围验证（2026-10-10）

对应 R15 第 1–3 步（基底核对、只改目标值及必要维护、完整性与变更范围验证）与 AC04 的 Calibre 一致性部分。本步只有元数据模块的纯本地处理，不访问存储后端、不推送；约束见[元数据约束](../src/main/java/io/github/chenxiex/calibrecloud/metadata/AGENTS.md#已读写回暂存)。

### 改动

- `metadata/ReadStatusStaging.kt`：输入私有快照、期望书库 UUID、栏目、变更列表与注入时钟；输出逐书结果（`CHANGED`／`ALREADY_TARGET`／`MISSING`／`IDENTITY_CHANGED`）与暂存文件（SHA-256、长度），全部已满足时不产生文件。整体失败：`LIBRARY_CHANGED`、`COLUMN_INVALID`、`INCOMPATIBLE`、`CORRUPT`、`NO_WRITABLE_BOOKS`、`INSUFFICIENT_SPACE`、`IO`。
- `verifyReadStatusChanges`：只读 `ATTACH` 原快照的变更范围验证（文件头不变字段、schema、`user_version`、`application_id`、非虚表逐表双向差集与行数），目标行的值类型、`last_modified` 与 `metadata_dirtied` 逐书核对。
- `app/verification/tools/calibre_db_diff.py`：只读、immutable 打开两个数据库，按带存储类别的行多重集逐表比较，相同时退出码 0，不同为 1。

与计划的工程差异：

- 结构验证直接调用 `CalibreSnapshotParser.parse`（与同步相同的完整解析），另查 `books.last_modified`、`metadata_dirtied.book`、`custom_column_N(id,book,value)`。
- 增加 `LIBRARY_CHANGED`（书库 UUID 与期望不符）和 `IO` 两个失败原因；变更范围不符按 `INCOMPATIBLE` 处理（例如源库有额外触发器改动其它表）。
- 只接受回滚日志格式的数据库：WAL 头在打开时会被改写，超出写回范围。
- 空间检查（快照两倍）放在暂存内，处理器在步骤 03 不再重复。

### 自动检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

结果 `BUILD SUCCESSFUL`，JVM **153 tests、0 failures**；lint 首次多出 2 条 `UsableSpace` 警告，按 `FormatCopyTaskHandler` 的做法以 `@SuppressLint` 与 KDoc 理由处理后回到 0 error、5 warning（AGP 与依赖版本提示，与步骤 01 相同）。

### 真机平台测试

设备 PA6（无线调试）。`aapt2 dump badging` 核对 debug application ID 为 `io.github.chenxiex.calibrecloud.debug`，`adb install -r -t` 安装 debug 与测试 APK：

```bash
adb shell am instrument -w -r -e class io.github.chenxiex.calibrecloud.metadata.ReadStatusStagingTest,io.github.chenxiex.calibrecloud.metadata.CalibreSnapshotParserTest io.github.chenxiex.calibrecloud.debug.test/io.github.chenxiex.calibrecloud.AwakeTestRunner
```

- 首次：13 tests，1 failure。失败在测试侧：篡改用例 `UPDATE books SET has_cover=0` 的辅助连接未注册 `title_sort`（即探测记录的 `books_update_trg` 准备期依赖），生产代码未受影响。辅助函数注册恒等 `title_sort` 后重跑：**OK (13 tests)**（7.0 s）。
- `ReadStatusStagingTest`（7 项）以仓库样本（Calibre 9.14，含 FTS5 虚表）加按 Calibre 自身 DDL 复制的第二个布尔栏目 `#favorite` 和含引号与中文的标题为 fixture，覆盖：标已读（空值→是、否→是、已是）；标未读（空值→显式 0、是→0），值存为 integer；同一列表混合目标；全部已满足时无文件、无 `.part`；UUID 变化与书籍删除逐书报告且不改写，全部失效为 `NO_WRITABLE_BOOKS`；栏目改名、标记删除、删除、改为 `int` 为 `COLUMN_INVALID`，书库 UUID 变化、截断文件、空间不足分别拒绝且无输出；快照字节不变；`last_modified` 仅目标书改为注入时间、`metadata_dirtied` 仅目标书、`sqlite_sequence` 仅该表加 2、第二个布尔栏目与标题不变；范围验证拒绝人为多改其它栏目、目标书其它列、非目标书 `last_modified`、多插脏记录、把值改成文本，以及与目标不符的值；零微秒时间戳格式。

### Calibre 一致性复核

测试把基底、标已读（书 1、5 改为是，书 4 已是）和标未读（书 1、4 改为否，书 5 已否）结果写到 debug 外部私有目录 `read-status-staging/`，`adb pull` 到容器 scratchpad 后：

- `calibre_db_diff.py base.db mark-read.db`／`mark-unread.db`：只有目标书的 `books.last_modified`、`custom_column_1` 行替换（新 id 7、8）、`metadata_dirtied` 新增目标书、`sqlite_sequence.custom_column_1` 6→8。
- 同一基底放入样本书库副本，用 `/opt/calibre/calibredb set_custom` 按相同顺序写入后，与本应用结果比较：唯一差异是两本书的 `last_modified` 时间值，以及 Calibre 打开书库的副作用 `preferences.last_expired_trash_at`；行 id、值和序号完全一致。
- 本应用结果放入样本书库副本后 `calibredb check_library` 各类别均无条目（退出码 0），`calibredb list --fields title,*read_status,*favorite` 读回标已读为三本 True、标未读为三本 False，`#favorite` 保持 True／False／None。

测试后已卸载 debug 与测试包（外部私有目录随之删除），设备上只保留正式应用（未触碰）。

### 未完成

本步不涉及源访问、推送、冲突或进程中断；这些在步骤 03–07 验证。用户共同验收尚未进行。

## 步骤 03：写回处理器、提交服务与统一源接口（2026-10-10）

对应 R14（提交冻结、栏目切换后旧任务失败、逐书失效）、R15（每轮重新获取、整轮重试、无需修改不推送）、R16（推送后紧接同步）、R13（待写入状态在执行各阶段的推导）、R33（写回日志）与 AC04 中可用 fixture 后端覆盖的部分。后端推送尚未实现，本步没有真实源写入。

### 改动

- `LibrarySource` 增加 `writeCapability`（不联网，返回 `WriteBlock?`）、`pushDatabase`（基底版本前提，`PushOutcome.Pushed`／`Conflict`，其它失败抛 `SourceFailure`）与 `finishPendingPush`，以及后端私有的持久 `PushJournal`。本地与 OneDrive 返回 `WriteBlock.NOT_IMPLEMENTED`，推送抛出 `UNSUPPORTED_OPERATION`，收尾为空操作。
- `tasks/readstatus/ReadStatusWriteTaskHandler`：三阶段执行、整轮重试与紧接同步，规则见[任务约束](../src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md#已读写回任务)；日志一行记录 task ID、阶段、轮次、书籍数、结果分类与耗时。
- `tasks/readstatus/ReadStatusService`：从当前导入冻结书籍、取有效已读栏目、复核选择代号、源不可写时拒绝。
- `MetadataRepository.readStatusPreference`：从 `library_preferences` 读取书库 UUID 与已读栏目（清元数据后仍保留）。
- 应用容器注册处理器与服务；`CacheMaintenance` 的保留规则与清理撤销一并删除 `write-staging/<task>`。

与计划的工程差异：

- `pushDatabase`／`finishPendingPush` 不带 `control` 参数：提交与收尾都不可暂停或取消。checkpoint 参数改为后端解释的 `PushJournal`（任务私有文件），队列的 `RecoveryCheckpoint` 只记录本轮代次与基底版本。
- 准备结果（暂存文件、散列、逐书失效）只在同一轮的阶段之间保存在内存，因为任何恢复都回到 `WRITE_SNAPSHOT`；暂存与基底文件放在处理器自己的 `write-staging/<task>/`。
- 暂存的块边界控制经 `runBlocking` 调用挂起的 `checkControl()`，保持步骤 02 的同步接口不变。
- 暂存失败的映射：`COLUMN_INVALID` → `InvalidColumn`；`IO` → 以 `LOCAL_IO` 重试一整轮；`NO_WRITABLE_BOOKS` → `SOURCE_MISSING` 失败（全部书籍失效，不推送、不同步）；`LIBRARY_CHANGED`、`INCOMPATIBLE` → `INCOMPATIBLE_DATABASE`；`CORRUPT` → `CORRUPT_CONTENT`；`INSUFFICIENT_SPACE` 同名失败。
- 书库已不是当前书库时返回等待 `INACTIVE_LIBRARY`（与协调器的停放一致）；`NO_NETWORK` 与源 `VERSION_CONFLICT`（如本地快照读取期间源变化）同推送被拒一样重试一整轮。

### 自动检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

结果 `BUILD SUCCESSFUL`（2m 3s），JVM **153 tests、0 failures、0 errors、0 skipped**；lint 0 error、5 warning（与步骤 02 相同，新文件无条目）。

### 真机平台测试

设备 PA6（无线调试），经 Gradle `connectedDebugAndroidTest` 安装 debug（`io.github.chenxiex.calibrecloud.debug`）与测试包并按 `AwakeTestRunner` 定向执行：

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.chenxiex.calibrecloud.tasks.readstatus.ReadStatusWriteTaskHandlerTest,io.github.chenxiex.calibrecloud.storage.cache.CacheMaintenanceTest
```

- 首次：`CacheMaintenanceTest` **19/19 通过**；`ReadStatusWriteTaskHandlerTest` 10 项中 1 项失败，原因在测试侧：书籍 UUID 被改动后，紧接的同步按既有导入规则为该书库分配了新的 LibraryId，测试在同步后才按新 ID 构造期望的 BookKey。改为在执行前取得 BookKey 后单独重跑 `ReadStatusWriteTaskHandlerTest`：**10/10 通过**。随后把紧接同步用例的插入任务由低优先级自动任务改为推送期间提交的高优先级请求（低优先级任务本来就排在同步之后，不能证明紧接），再次单独重跑：**10/10 通过**。

`ReadStatusWriteTaskHandlerTest` 使用生产的处理器、服务、队列、协调器、暂存与书库同步处理器，真实平台 SQLite 与私有文件；源为内存中的 fixture（仓库 Calibre 9.14 样本加第二个布尔栏目 `#favorite`），推送时核对基底版本与暂存散列，可模拟其它写入者。覆盖：

- 标已读（空值、否 → 是）后同步紧接写回执行：推送期间提交的高优先级用户请求序号在同步之前，仍排在同步之后；导入在同步后才显示已读，待写入在同步结束后为空，`write-staging` 无残留；再标未读写入显式 0 行。
- 离线（条件注入等待网络）时任务等待、源与导入不变、显示待写入；恢复网络后完成。
- 第一次推送前另一写入者修改 `#favorite`：推送被拒，第二轮取得最新数据库写入，两处修改都保留。
- 推送一直被拒：共 4 轮后以 `VERSION_CONFLICT` 失败、源不变、待写入显示失败；手动重试恢复待写入并在一轮内完成。
- 推送成功后以取消协程结束派发、重开状态库与协调器（协议恢复，不是真实进程终止）：重跑一轮发现已是目标值，不再推送，直接完成并同步。
- 提交后切换已读栏目：旧任务在准备阶段以 `InvalidColumn` 失败，没有推送。
- 一本书 UUID 变化：其余书写入，该书以 `BookIdentityChanged` 记入 `CompletedWithBookFailures`，仍提交同步。
- 空间不足：准备阶段失败，没有推送，无残留文件。
- 任务开始前三次点击（含同一本书改目标）并入一个任务，一次推送写入最后的目标。
- 全部已是目标值：不推送，完成并同步。

测试结束后 Gradle 已卸载 debug 与测试包，设备上只保留正式应用（未触碰）。

### 未完成

真实本地与 OneDrive 推送、三步重命名收尾、真实冲突与真实进程终止在步骤 04、05、07 验证；界面入口与待写入显示在步骤 06。用户共同验收尚未进行。
