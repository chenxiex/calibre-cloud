# 本地目录授权开发约束

本目录遵循 [存储约束](../AGENTS.md)；覆盖 R04、R07、R20、R31–R35 的平台授权入口。授权组件只保存未验证的本地位置，不分配书库身份，不读取或枚举源文件。显式候选任务通过 `LocalSourceBackend` 读取源并取得只读私有快照；快照交给元数据模块验证 Calibre 结构并原子导入／激活书库，后端自身不解析完整元数据。

## 授权边界

- UI 通过 Activity Result 发起 `ACTION_OPEN_DOCUMENT_TREE`，把结果 URI 和实际返回 flags 转交 `LocalDirectoryAuthorization`；不能自行解析绝对路径或调用源文件 API。
- `AndroidDirectoryPermissions` 首轮只接受系统包 `com.android.externalstorage` 的 `com.android.externalstorage.documents` tree URI。Manifest 仅查询此 authority；未知 OEM 提供方和云端提供方都不能自动当成本地。调整识别规则前需要对应设备／提供方证据。
- 请求读写和持久授权，申请持久权限时只使用实际返回的读写 flags；缺少持久或读取 flags 拒绝保存。恢复时以 OS 的持久授权清单为准，区分读写、只读与撤销。权限 flags 不证明数据库写回安全或目录仍存在；实际文件可用性和受控写回能力由后续后端探测。
- 新授权必须已可读且配置 durable commit 成功，才释放不同的旧授权。取消和拒绝保持旧选择；保存失败清理新授权，同 URI 失败不能释放当前授权。配置失败时 SQLite 事务回滚，保持旧选择。
- 私有状态库在同一事务保存 tree URI 与当前候选位置／选择代号；不分配 LibraryId。`local_directory` SharedPreferences 仅作第一阶段配置的兼容读取，已有数据库配置优先，读取旧授权不覆盖新的当前候选。授权状态从 OS 重新计算，配置排除备份和设备迁移，不属于凭据存储，不记录 URI 或用户路径。详见 [状态库约束](../../state/AGENTS.md)。
- 配置与平台授权访问在注入的 I/O dispatcher 上串行执行。授权组件没有源流、读取、写入、复制或任务执行接口；首页恢复授权不触发同步。
- UI 使用跨 Activity 重建保留的 ViewModel，授权操作和结果交付串行；恢复不能覆盖尚未结束的选择，busy 只在所有已提交授权操作结束后解除。进程重启仍以私有配置和系统持久授权恢复。

## 入口与验证

当前入口用文字显示未选择、目录授权／书库待验证、只读、需重新授权及不支持提供方；失败保留原选择并提供重新选择按钮。应用控制的按钮无 ripple／过渡，入口使用显式两页切换，无滚动；系统选择器不受应用动画约束。

JVM 测试使用授权元数据与配置适配器。`LocalDirectoryAuthorizationDeviceTest` 必须在通过真实系统选择器授权专用测试书库后单独运行；该测试重建 Activity 并撤销这次 debug 持久授权，不对源文件操作。不要将缺少准备数据的失败当成产品授权失败，也不要在通用无人值守设备测试中把它当成无需前置条件的用例。

实际命令、设备结果和待完成验收见 [第一阶段验证记录](../../../../../../../../../verification/phase-1.md)。

第二阶段配置持久化的新增证据见 [第二阶段验证记录](../../../../../../../../../verification/phase-2.md)；真实授权用例仍需系统选择器前置准备。

## 显式源读取与快照

- `AndroidLocalDocumentAccess` 仅支持已识别的系统 external-storage 提供方，每次访问复核实际持久读取授权。只使用 tree 内 document URI 和只读流，不转换源绝对路径，不提供源写入 API。按显示名逐级解析并复核外部存储文档 ID 根范围，拒绝重复名称、目录冒充文件及树外定位。
- 单格式副本续传通过只读文件描述符探测 `lseek`，定位成功后仅提供断点之后的流；不可寻址提供方返回不支持范围读取，由任务层重新完整传输，不用读取并跳过前缀伪装续传。
- 内容版本使用完整流 SHA-256，不依赖提供方的时间／大小字段。文件可写标志仅表达提供方能力，不代表已授权写回或安全提交能力。
- `LocalSourceBackend.acquireSnapshot` 为每次候选任务创建独立私有暂存和不可变文件代次；复制 hash 必须与第二次完整源读取一致，并复查 `metadata.db` 文档 ID 和事务日志。非空 WAL／rollback journal、SHM 及 master journal 无一致快照获取路径时拒绝同步；空 WAL／journal 实际读流确认。此协议不宣称任意并发写入安全。
- `AndroidSnapshotValidator` 只对私有副本执行 `OPEN_READONLY` 和 SQLite `integrity_check`；禁用默认损坏文件删除处理，不进行修复、迁移、checkpoint 或源库升级。校验和发布前再次检查队列控制，取消或失败只清理本次暂存，保留之前成功快照。
- 快照按候选任务 UUID 隔离，不分配 LibraryId 或激活书库；源缺失、授权撤销、版本冲突、损坏内容和本地 I/O 等失败用结构化原因返回，不携带路径、URI 或异常原文。
- JVM 后端测试使用只读文档适配器验证解析、双读、日志、失败保留与控制中断；`LocalSnapshotIntegrityTest` 使用真实 Android SQLite 验证私有完整性检查。两者不替代目标设备系统 SAF 获取与源内容不变验证，实际证据在第二阶段验证记录就近维护。
