# 元数据导入约束

- 只接收存储后端完成并验证一致性的私有数据库快照；不依赖 SAF、Graph 或源目录，不对数据库迁移、修复、checkpoint 或创建缺失栏目。
- `CalibreSnapshotParser` 使用 Android SQLite `OPEN_READONLY`，禁用会删除损坏文件的默认错误处理，先检查完整性，再检查必需表、列及导入关系。版本号不作为兼容依据；所有解析结果必须先通过身份、路径、格式、关系及支持栏目布局校验才能发布。
- 结构依据为 [Calibre 9.14.0 backend.py](https://github.com/kovidgoyal/calibre/blob/v9.14.0/src/calibre/db/backend.py) 的自定义栏目创建／读取协议与库 UUID，以及 [schema.sql](https://github.com/kovidgoyal/calibre/blob/v9.14.0/resources/metadata_sqlite.sql) 的标准表。布尔为 `custom_column_N(book,value)`；文本、枚举和多值文本采用 `custom_column_N(id,value)` 加 `books_custom_column_N_link(book,value)`。动态 SQL 仅使用固定表名或已验证的正整数栏目 ID；不插入源 label/name，不执行计算栏目或模板。
- `ParsedLibrary` 保留源书库 UUID 与书籍 ID／UUID 证据，应用的 LibraryId 和代次另行分配。索引 JSON 是私有派生格式，不能上传作为源数据库。路径只能是通过模型验证的书库相对定位，后端还须校验最终解析不越出授权目录。
- 布尔空值通过缺失值或 `Bool(null)` 表示；只有有效已配置栏目允许把空／否解释为未读。栏目不存在、改名或类型改变是配置问题，不能产生全库未读的推断。
- 平台 fixture 按上述 9.14.0 布局创建，仅验证实际解析能力；构造的最小 fixture 不等于经过 Calibre 桌面程序打开的完整书库。仓库 `assets/calibre-sample/metadata.db` 另由用户通过桌面程序维护，实库测试在私有副本验证导入与布尔栏目配置；副本上的程序变更不作为桌面程序改名／删除／改类型的证据。样本覆盖范围与结果见 `app/verification/phase-2.md`。

## 已读写回暂存

- `ReadStatusStaging` 只在后端取得的最新私有快照的副本上生成待推送数据库，不访问存储后端、不读派生索引、不改动快照。先用 `CalibreSnapshotParser` 完整解析，再核对书库 UUID、栏目（ID 与 lookup 一致、`bool`、单值、未标记删除）和每本书的 ID／UUID；失效书籍逐书报告，不按数字 ID 改写其它书。
- 修改范围以 Calibre 9.14 `set_custom` 实测差异为准（见 [第四阶段记录](../../../../../../../../verification/phase-4.md)）：替换 `custom_column_N` 行（显式 1／0）、`books.last_modified` 设为注入时钟的 UTC（Python `isoformat(' ')` 格式）、`metadata_dirtied` 插入该书；已是目标值的书不修改，全部已满足时不产生文件。`title_sort` 注册为被调用即失败，只为通过 `books_update_trg` 的语句准备。
- 打开参数固定 `NO_LOCALIZED_COLLATORS`（不建 `android_metadata`）、`DELETE` 日志、空错误处理器；只接受回滚日志格式（文件头 18／19 字节为 1）。提交后以只读连接 `ATTACH` 原快照验证：文件头不变字段、`sqlite_master`、`user_version`、`application_id` 相同，非虚表（含 FTS 影子表）逐表以 `typeof` 加 BINARY 比较双向差集和行数，只允许上述行变化；再 `integrity_check`、确认无日志文件、fsync 并计算 SHA-256。表名取自快照自身的 `sqlite_master`，一律按标识符引用。任何失败删除 `.part`。
- 容器复核工具 [calibre_db_diff.py](../../../../../../../../verification/tools/calibre_db_diff.py) 以 `python3 -I` 只读比较两个数据库。

## 发布与恢复

- `MetadataRepository` 在独立 UUID 目录 fsync 完整快照，完成索引构建后以同一应用状态事务发布 library_bindings、metadata_imports 和 metadata_books。JSON 保留完整关联数据；关系表提供 book ID／UUID 唯一性与本地身份索引，后续查询不能为缺失缓存隐式读取源。
- 发布事务重新核对当前选择代号与稳定位置，拒绝过期代号、暂停、取消或 revoked 请求，并在同一事务记录任务完成；发布后的控制请求被拒绝，不会把已发布结果标成暂停或取消。事务失败删除本次未发布文件，保留旧快照与索引；导入只回收本目录中数据库未引用的 UUID 代次，不访问源或其它缓存目录。
- 位置隔离后优先比较可用的源书库 UUID，同时检测仍存在的数字书籍 ID 是否更换 UUID。不把数据库哈希、修改时间或目录显示名当书库身份。没有可用 UUID 或重叠书籍证据时不声称可检测任意替换；书籍副本始终另以完整 book ID／UUID 隔离。
- 重选已知位置恢复最后有效私有导入，不隐式验证源；显式新导入发现不兼容替换时分配新的 LibraryId 与书库代次，保留其它身份的数据。栏目配置随书库身份保存，重新导入只更新来源值，不重置或覆盖失效配置；保存设置使用当前身份与导入代次比较，拒绝旧页面提交。

- 导入事务按独立最小清单中的完整 CopyKey 创建持久低优先级 `FormatCheck`，不探测源、不下载缺失副本或其他格式。检查随导入原子入队，进程在下一次调度前中断不会丢失更新检查；执行时通过存储后端核对文件版本，确认变化才追加固定版本前提的更新任务。
- 入队范围由源的 `checksCopy` 决定。OneDrive 只为 Calibre 记录有变化的副本入队（Q53）：清单记录副本发布或确认未变时所依据导入的 `books.last_modified`（原文比较）与格式大小，新导入两者都相同就不检查；没有记录（schema v9 之前下载）、记录不同或书籍／格式已不在导入中的仍入队，检查确认版本未变时补记新导入的值。Calibre 9.14.0 实测：`calibredb add_format` 替换格式时更新该书 `last_modified`，内容不同时大小也随之改变；不经 Calibre 直接改动书库文件不会被发现。本地书库仍为每个已下载格式入队。
- 同步入口 `sync(source, …)` 取上次导入的 `source_version` 交给 `acquireSnapshot`，再导入快照或确认未变；`importSnapshot` 的 `sourceVersion` 为读取快照时的版本，各后端都保存。OneDrive 取得相同 cTag 时返回 null，由 `confirmUnchanged` 在与导入相同的选择代号与任务控制门内只更新导入时间并完成任务，不改动导入代次、派生视图与检查（Q55）。本地快照总是完整读取，因为确认未变本身需要读完全部内容。清元数据删除该记录，之后的同步完整读取。

## 元数据清理后的身份与配置

`library_preferences` 独立保留源书库 UUID、栏目 ID／lookup 和最后导入时间，v5 迁移从既有私有导入补全证据；清理移除 `metadata_imports`／`metadata_books` 和快照／封面，不删除偏好、绑定或下载清单。此时 `currentImport()` 明确为空，不能用下载清单重建完整分类或搜索；源状态尚未重新确认。

显式重新加载仍执行完整后端快照／导入流程。没有旧完整索引时，兼容性比较使用持久源书库 UUID 和保留副本的数字 ID／UUID，符合证据才复用 LibraryId 和栏目配置，冲突分配新身份；没有充分证据时不能保证检测任意源替换。新的用户加载可创建新任务，清理只撤销既有任务，不设置永久禁用加载或自动同步的标记。

完整导入的 UUID 目录记录私有 `library-id` 所属标记，发布后及时回收旧代次；若回收中断，后续清理按所属标记纳入同库残留快照，不影响其它库有效导入。标记只用于应用缓存归属，不是源身份或完整索引。
