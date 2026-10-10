# 存储开发约束

本文件适用于 `storage/`；同时遵循 [Android 模块约束](../../../../../../../../AGENTS.md)。需求以 [spec.md](../../../../../../../../../spec.md) 的 R03–R07、R28、R31–R33 为准。当前已有持久下载清单与应用副本读取器，统一由应用依赖容器构造；[状态库约束](../state/AGENTS.md)定义候选位置、绑定与最小清单边界。本地只读 SAF 后端和候选快照任务已实现，[OneDrive 只读后端](onedrive/AGENTS.md) 已接入个人目录浏览、源内容与快照；完整元数据导入、书籍副本传输及导入后的已下载格式版本检查已实现。

## 身份依赖

身份、格式、源定位和逻辑路径模型位于相邻的 `model/`，修改这些模型时遵循 [模型开发约束](../model/AGENTS.md)。存储实现须使用完整身份键，不按书名或数字书籍 ID 单独匹配副本。

本地 SAF 授权入口的提供方识别、实际 flags、每个书库的授权与释放规则见 [本地授权约束](local/AGENTS.md)。授权组件仅授权位置；独立的本地源后端只由显式候选任务访问源文件，取得私有一致 SQLite 快照后交给统一导入器；格式源读取只在显式副本任务中执行。

## 完整副本读取

`PrivateCopyReader` 只依赖 `CompleteCopyQuery`、应用句柄工厂和注入的 I/O dispatcher。查询只能返回已经验证并原子发布的完整记录；暂存和执行中任务不进入该查询。读取返回 `Available(handle, savedVersion)`、`Missing` 或结构化失败，没有源回退、隐式下载或等待任务选项。源尚未确认或已确认缺失不会阻止完整旧副本读取；网络／授权失败不能更改清单的源状态。

调用方拥有 `ApplicationCopyHandle`，必须关闭并在后台执行后续流读取；取消发生在结果交付前时，读取器会关闭未交出的句柄。维护必须协调使用中句柄与不可变文件代次：先验证暂存内容，再发布新记录；不得原地改写已发布文件或重用文件代次。生产读取器与清单发布共享 `state.copyAccess`，序列化完整记录查询与句柄打开，防止旧记录查询后文件被回收；文件工厂跟踪活跃句柄，旧代次在最后一个句柄关闭后回收。

## 精确缓存清理

[CacheMaintenance](cache/CacheMaintenance.kt) 只依赖私有状态、文件和共享队列，没有源访问依赖。预览冻结书库、书籍 ID／UUID 和格式集合，显示完整副本数与应用空间；`formats = null` 表示所选书籍的全部格式，非空集合只匹配指定格式。确认事务复核选择代号，撤销范围内旧生产任务、移除查询引用并写入持久清理 journal，再等共享执行锁到安全边界删除文件。恢复先完成 journal 才允许队列继续派发；不得重新读取 UI 筛选扩大范围。

当前元数据清理删除快照、完整索引和封面，撤销既有普通同步／封面加载，保留书籍副本、最小清单、配置、已读任务及保护资料；保留清单的源状态重置为尚未确认。其它书库清理只枚举本地非当前 LibraryId，清理其副本、索引、封面及可确定归属的任务暂存，不要求源可访问；该书库的已读写回一并撤销。任务归属与旧版本证据匹配规则见[任务约束](../tasks/AGENTS.md)。

图书馆选择模式的“移除下载”（R26）直接使用本预览／确认协议：页面在预览时把当前格式筛选冻结为 `formats`，确认页展示的计划原样执行，之后改变筛选不影响范围。

当前书库按格式移除仅 retire 冻结范围内的完整文件代次和对应任务 checkpoint 代次，不对整个 books 目录做无引用回收；清元数据不 retire 书籍副本。其它书库清理可在共享执行锁内回收该书库的无引用代次。活跃句柄延迟至关闭后回收，实际删除失败须保留恢复证据并报告未完成。

## 显式操作和文件提供

## 统一源接口

已激活书库的全部源访问经 [LibrarySource](api/LibrarySource.kt)：按路径观察文件（`lookup`，返回版本、大小、可选内容散列与流）、发布前核对（`unchanged`）、封面（`openCover`）、元数据快照（`acquireSnapshot`，源未变时可返回 null）以及已读写回的能力判断与推送（`writeCapability`、`pushDatabase`、`finishPendingPush`），失败统一为 `SourceFailure`。每个后端实现完整契约（[本地](local/LocalLibrarySource.kt)、[OneDrive](onedrive/OneDriveLibrarySource.kt)）；规格按后端区分的行为由后端声明，调用方不判断后端类型：`requiresNetwork`、`resyncsMissingPath`（R11 路径失效先同步）、`checksCopy`（R11 导入后检查范围）、`reauthorization`（等待登录或目录授权）。节省请求的规则（如 OneDrive 一次查找供后续读取、发布前不复查）在操作内部实现；限流以 `THROTTLED` 及等待时间表达，由队列对所属书库统一生效，不需要额外接口。新增后端只需实现该接口并在应用容器的 `librarySources` 中注册。

书库位置由 [LocationKeys](api/LocationKeys.kt) 编码为不透明键：状态库只保存后端代码和该键，只比较相等，不解析其组成，新增后端不改 schema。同步任务所需的授权由各后端实现 [LibraryAuthorization](../tasks/api/LibraryAuthorization.kt)，见[任务约束](../tasks/AGENTS.md)。

按用户确认，只有以下两类按设计与具体后端绑定，不经统一接口：登录与目录授权本身（`auth/`、本地授权组件），以及选择书库根（OneDrive 目录浏览及其 `OneDriveCandidateTaskHandler`／`OneDriveCandidateService`、SAF 选择器）。选中根之后的同步、恢复与源访问都走统一接口。

`CopyMaintenance` 仅声明精确副本移除和元数据清理，由 `CacheMaintenance` 实现。没有一般源上传／删除接口。唯一的源写入是已读写回：书籍与目标值保存在任务队列的变更列表中（见[任务约束](../tasks/AGENTS.md#已读写回任务)）。`writeCapability` 不联网，给出不可写原因 `WriteBlock`；`pushDatabase` 只在源仍为基底版本时替换 `metadata.db`，被拒返回 `PushOutcome.Conflict`，结果不明抛出可重试的 `SourceFailure`，不可暂停或取消；分步提交的后端在每步前把进度写入任务的 `PushJournal`，下一轮开始前由 `finishPendingPush` 收尾。两个后端目前返回 `WriteBlock.NOT_IMPLEMENTED`、推送抛出 `UNSUPPORTED_OPERATION`，本地与 OneDrive 的提交分别在第四阶段步骤 04、05 实现。

文件工厂、书籍 provider 的提供范围、完整文件发布及只读授权规则见 [文件提供开发约束](../files/AGENTS.md)。

必要测试分别位于 `app/src/test` 的身份／副本读取测试和 `app/src/androidTest` 的 provider 路径测试；实际结果与未完成验收见 [第一阶段验证记录](../../../../../../../../verification/phase-1.md)。

持久查询的真实 SQLite、事务与文件状态测试及本阶段结果见 [第二阶段验证记录](../../../../../../../../verification/phase-2.md)。

## 后端测试与验收边界

遵循 Android 模块的分层测试与验收规则。可插拔后端的共同契约在接口处验证：使用确定性的源版本、流和结构化失败，覆盖读取、范围读取、授权／网络失败及边界拒绝；不把这些组合逐项转成人工端到端操作。涉及真实 SQLite、文件发布与句柄的行为使用平台数据库及文件验证，不以纯内存替身代替。

每个后端仍须完成必要的真实集成检测：本地使用真实 SAF 提供方和授权，远程使用生产后端访问已授权的专用测试源，验证本次新增的实际 API 路径。读取或范围读取的接口 fixture 通过，不能代替真实 SAF 或上游响应成功。只读检测可写 debug 应用自己的缓存、暂存、清单及队列；不得据此扩大源写入权限。需要真实源写回的功能另按该阶段的提交与冲突验收要求验证，不能以只读检测关闭验收。

独立按需封面缓存与真实加载处理器已实现，读取／发布／限额规则见[封面缓存](covers/AGENTS.md)。普通封面读取没有源回退，缺失时由可见页显式提交持久低优先级任务。
