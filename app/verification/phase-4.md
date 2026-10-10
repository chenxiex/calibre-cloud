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
