# 应用持久状态约束

本目录遵循 [Android 模块约束](../../../../../../../../AGENTS.md)，实现 R03–R06、R12、R32–R34 的应用状态基础。Schema、事务和 API 语义就近见 [数据库](ApplicationStateDatabase.kt) 与 [repository](ApplicationStateRepository.kt) 的 KDoc；此处不保存 Calibre 源数据库。

- 应用依赖容器按进程共享一个 SQLiteOpenHelper、repository、本地授权组件和副本读取器。数据库及文件操作在 I/O dispatcher 上执行。
- 书库列表（`configured_libraries`）、当前选择（位置、选择代号）与已验证身份分开持久化。列表按添加顺序保存位置键、目录显示名和后端给出的不透明访问键（本地为该书库的 tree URI），不分配 LibraryId；`switchTo` 只能选择列表中的位置。进行中的添加（`library_addition`）有自己的代号和授权会话，选择根只写入添加记录，`completeAddition` 才在一个事务内加入或更新列表项并选择该位置，返回不再使用的旧访问键；同一位置再次添加只更新名称和访问键。当前选择总有稳定位置，不能伪造根目录或 LibraryId。重选立即生成新代号，并只恢复该位置已有有效导入的身份引用，否则清除当前身份引用，不删除历史绑定或清单；切回已导入位置通过私有索引恢复最后有效绑定，不触发源访问；新的显式导入重新核验兼容性。不以名称、修改时间或数字书籍 ID 生成身份。
- `chooseAddition` 在同一事务核对添加代号与后端，过期浏览结果不能写入较新的添加；`beginAddition` 替换旧添加并返回它以便释放其授权。添加在完成前不改变列表或当前选择，也不激活书库。
- `bindValidated` 保留为验证绑定的窄接口；生产完整导入由 `MetadataRepository` 在同一状态数据库事务发布绑定及导入引用。过期代号不得发布绑定；同一个 LibraryId 不能改绑位置或代次，不兼容替换分配新的 LibraryId 和代次。持久队列按当前身份停放其它库任务，按选择代号拒绝过期候选请求；界面打开意图在交给阅读器前核对选择代号。
- 访问键随列表项保存，`replaceAccessKey`／`accessKeyInUse` 供授权组件在替换后释放不再被任何书库或添加使用的旧权限。OneDrive 凭据仍在原加密存储，状态库不能保存令牌、下载 URL 或源流。
- 最小清单以 LibraryId、源数字 ID、源 UUID、格式为复合键，只保存完整文件代次、友好标题、可空大小、源版本和明确源状态，不充当元数据索引。查询不探测源，读取错误不删除记录，也不将源状态改为缺失。
- 完整记录发布前检查私有文件可读、非空及已知大小。格式内容与源版本验证由真实副本任务处理器负责；不能把长度校验当成格式验证。生产发布核对当前书库及任务控制，在同一事务保存完整清单与任务成功；暂存、快照、索引、封面和保护资料不能放入 books 或进入完整查询。
- Schema 使用显式持久化 code、外键、约束和事务。增加 schema 版本必须提供非破坏性迁移，保留清单、任务及恢复证据；不能删库重建或访问源库解决升级问题。
- 版本号只定义在 `ApplicationStateDatabase.VERSION`。提升版本时，在 androidTest 的 [StateSchemaHistory](../../../../../../../androidTest/java/io/github/chenxiex/calibrecloud/state/StateSchemaHistory.kt) 增加新版本的逆变换（使用冻结的旧 DDL）；未增加前全部迁移测试在该处以明确提示失败，这是升级唯一需要改的测试代码。迁移测试只经它构造旧 schema，断言升级后版本等于 `VERSION`，不写版本字面量；测试写状态库表时列出列名，不按位置插入。用 `rg StateSchemaHistory` 可找到全部迁移测试。

平台 SQLite 验证见 [第二阶段验证记录](../../../../../../../../verification/phase-2.md)。真实下载／发布、句柄回收及精确清理协调已实现。

- Schema v3 非破坏性新增 metadata_imports 与 metadata_books，保留 v1/v2 清单、任务及授权。完整元数据保存在 metadata_imports，最小清单不扩充为完整索引。导入失败不更新绑定或当前引用，快照代次的文件恢复协议见元数据模块。

- Schema v4 非破坏性新增 `cover_cache`，保留 v1–v3 的绑定、导入、清单和任务；完整封面记录与加载任务成功在同一事务发布，目录与限额契约见[封面缓存](../storage/covers/AGENTS.md)。

- Schema v5 非破坏性增加 `cache_cleanup` journal、任务的 `revoked`／`scope_library_id` 和独立 `library_preferences`。迁移从既有私有导入保存源书库 UUID、已读栏目身份及最后导入时间；清元数据不删除这些身份／配置证据。候选任务仅按可核对的位置、选择代号或无歧义私有快照证据补归属，不凭目录名称推断；没有充分证据的旧任务不能宣称已准确归属。
- 清理事务同时保存冻结范围、待删私有路径、待 retire 文件代次及任务撤销标记，并移除相应元数据／清单引用。生产发布须在同一事务拒绝 revoked 任务。保留的完整副本在元数据清理后仍可读取，源状态改为 `UNCONFIRMED`；配置、绑定及保护资料不随普通缓存清理删除。具体文件删除和恢复由[缓存维护](../storage/cache/CacheMaintenance.kt)负责，不在状态库操作中访问源。

- Schema v7 非破坏性增加 `search_history`（书库 ID、查询文字、顺序号），由 [SearchHistoryRepository](SearchHistoryRepository.kt) 读写：只保存执行过的非空查询，重复查询移到最前，每库保留最近 50 条。历史属于书库绑定，清元数据、移除副本和清理其它书库缓存都不删除它，清除历史只删当前书库的记录；查询文字不得写入日志。
- Schema v9 非破坏性增加 OneDrive 请求开销状态：`metadata_imports.source_version`、清单的 `calibre_recorded`／`calibre_modified`／`calibre_size`、任务的 `source_sync`／`missing_path` 与 `source_throttle` 截止时间表；各列仅在缺失时添加，旧副本 `calibre_recorded=0`。
- Schema v10 把绑定与当前选择的按后端位置列（authority、root_id、account_id、drive_id）换成存储层 [LocationKeys](../storage/api/LocationKeys.kt) 的不透明 `location_key`（未选根的候选为 null），并去掉绑定、选择与清单上的后端 CHECK 约束；新增后端不再改 schema。迁移在延迟外键检查下重写父表，提交时有任何引用失去绑定即失败；其它行不变。
- Schema v8 非破坏性增加 `last_opened`（每个书库一条：书籍身份、格式、显示标题），由 [LastOpenedRepository](LastOpenedRepository.kt) 读写；只在系统打开调用成功后保存，不表示阅读进度或已读。清元数据、移除副本和清理其它书库缓存都保留它。
- Schema v11 非破坏性为 `application_settings` 增加 `format_priority`（逗号分隔的格式名，默认 `EPUB`），保存 R24 的全局格式顺序；未列出的格式由查询按名称排在后面。与启动设置一样不随任何缓存清理删除。
- Schema v12 增加 `configured_libraries` 与 `library_addition`：当前选择有位置时成为列表第一项，原单一 `local_authorization` 的 tree URI 迁为其访问键后删除该表；没有位置的旧候选选择被移除，迁移后没有当前书库。删除书库（[CacheMaintenance](../storage/cache/CacheMaintenance.kt) 的 `LIBRARY` 计划）在清理事务中删除该位置全部绑定的副本、索引、封面、偏好、搜索历史、上次打开和列表项，删除当前书库时一并删除当前选择；绑定行与保护资料保留。
- Schema v13 把任务等待的路径失效同步从 `queued_tasks.source_sync` 迁入 `task_dependencies`（要求 `awaited_sync`）；升级的数据库保留已清空的 `source_sync` 列（API 30 的 SQLite 不能删除列），新建数据库没有该列。
- Schema v15 非破坏性为 `application_settings` 增加可空的 `library_view`，保存界面编码的图书馆视图与筛选（R21，Q82）；状态库不解析其内容。与其它设置一样全局、不随任何缓存清理删除。
- Schema v6 非破坏性增加独立 `application_settings`，启动自动同步默认关闭；设置与书库代次无关，清元数据、移除副本和其它书库清理均保留它。读取／更新通过共享 repository 在 I/O dispatcher 上执行，不在 Activity 或 worker 另建状态库。
