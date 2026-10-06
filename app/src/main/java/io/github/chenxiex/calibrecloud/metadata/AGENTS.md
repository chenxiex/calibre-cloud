# 元数据导入约束

- 只接收存储后端完成并验证一致性的私有数据库快照；不依赖 SAF、Graph 或源目录，不对数据库迁移、修复、checkpoint 或创建缺失栏目。
- `CalibreSnapshotParser` 使用 Android SQLite `OPEN_READONLY`，禁用会删除损坏文件的默认错误处理，先检查完整性，再检查必需表、列及导入关系。版本号不作为兼容依据；所有解析结果必须先通过身份、路径、格式、关系及支持栏目布局校验才能发布。
- 结构依据为 [Calibre 9.14.0 backend.py](https://github.com/kovidgoyal/calibre/blob/v9.14.0/src/calibre/db/backend.py) 的自定义栏目创建／读取协议与库 UUID，以及 [schema.sql](https://github.com/kovidgoyal/calibre/blob/v9.14.0/resources/metadata_sqlite.sql) 的标准表。布尔为 `custom_column_N(book,value)`；文本、枚举和多值文本采用 `custom_column_N(id,value)` 加 `books_custom_column_N_link(book,value)`。动态 SQL 仅使用固定表名或已验证的正整数栏目 ID；不插入源 label/name，不执行计算栏目或模板。
- `ParsedLibrary` 保留源书库 UUID 与书籍 ID／UUID 证据，应用的 LibraryId 和代次另行分配。索引 JSON 是私有派生格式，不能上传作为源数据库。路径只能是通过模型验证的书库相对定位，后端还须校验最终解析不越出授权目录。
- 布尔空值通过缺失值或 `Bool(null)` 表示；只有有效已配置栏目允许把空／否解释为未读。栏目不存在、改名或类型改变是配置问题，不能产生全库未读的推断。
- 平台 fixture 按上述 9.14.0 布局创建，仅验证实际解析能力；构造的最小 fixture 不等于经过 Calibre 桌面程序打开的完整书库。仓库 `assets/calibre-sample/metadata.db` 另由用户通过桌面程序维护，实库测试在私有副本验证导入与布尔栏目配置；副本上的程序变更不作为桌面程序改名／删除／改类型的证据。样本覆盖范围与结果见 `app/verification/phase-2.md`。

## 发布与恢复

- `MetadataRepository` 在独立 UUID 目录 fsync 完整快照，完成索引构建后以同一应用状态事务发布 library_bindings、metadata_imports 和 metadata_books。JSON 保留完整关联数据；关系表提供 book ID／UUID 唯一性与本地身份索引，后续查询不能为缺失缓存隐式读取源。
- 发布事务重新核对当前选择代号与稳定位置，拒绝过期代号、暂停或取消请求，并在同一事务记录任务完成；发布后的控制请求被拒绝，不会把已发布结果标成暂停或取消。事务失败删除本次未发布文件，保留旧快照与索引；下次导入只回收本目录中数据库未引用的 UUID 代次，不访问源或其它缓存目录。
- 位置隔离后优先比较可用的源书库 UUID，同时检测仍存在的数字书籍 ID 是否更换 UUID。不把数据库哈希、修改时间或目录显示名当书库身份。没有可用 UUID 或重叠书籍证据时不声称可检测任意替换；书籍副本始终另以完整 book ID／UUID 隔离。
- 重选已知位置恢复最后有效私有导入，不隐式验证源；显式新导入发现不兼容替换时分配新的 LibraryId 与书库代次，保留其它身份的数据。栏目配置随书库身份保存，重新导入只更新来源值，不重置或覆盖失效配置；保存设置使用当前身份与导入代次比较，拒绝旧页面提交。

- 导入事务按独立最小清单中的完整 CopyKey 创建持久低优先级 `FormatCheck`，不探测源、不下载缺失副本或其他格式。检查随导入原子入队，进程在下一次调度前中断不会丢失更新检查；执行时通过存储后端核对文件版本，确认变化才追加固定版本前提的更新任务。
