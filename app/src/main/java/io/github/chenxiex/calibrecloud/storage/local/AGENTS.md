# 本地目录授权开发约束

本目录遵循 [存储约束](../AGENTS.md)；覆盖 R04、R07、R20、R31–R35 的平台授权入口。当前只保存未验证的本地位置，不分配书库身份，不读取或枚举源文件，不验证 `metadata.db`，不导入书籍。

## 授权边界

- UI 通过 Activity Result 发起 `ACTION_OPEN_DOCUMENT_TREE`，把结果 URI 和实际返回 flags 转交 `LocalDirectoryAuthorization`；不能自行解析绝对路径或调用源文件 API。
- `AndroidDirectoryPermissions` 首轮只接受系统包 `com.android.externalstorage` 的 `com.android.externalstorage.documents` tree URI。Manifest 仅查询此 authority；未知 OEM 提供方和云端提供方都不能自动当成本地。调整识别规则前需要对应设备／提供方证据。
- 请求读写和持久授权，申请持久权限时只使用实际返回的读写 flags；缺少持久或读取 flags 拒绝保存。恢复时以 OS 的持久授权清单为准，区分读写、只读与撤销。权限 flags 不证明数据库写回安全或目录仍存在；实际文件可用性和受控写回能力由后续后端探测。
- 新授权必须已可读且配置 durable commit 成功，才释放不同的旧授权。取消和拒绝保持旧选择；保存失败清理新授权，同 URI 失败不能释放当前授权。配置失败时恢复 SharedPreferences 的旧内存值。
- 私有 `local_directory` SharedPreferences 仅保存 tree URI；状态从 OS 重新计算，不保存可冒充书库验证／导入结果的布尔值。配置排除备份和设备迁移，不属于凭据存储，不记录 URI 或用户路径。
- 配置与平台授权访问在注入的 I/O dispatcher 上串行执行。授权组件没有源流、读取、写入、复制或任务执行接口；首页恢复授权不触发同步。
- UI 使用跨 Activity 重建保留的 ViewModel，授权操作和结果交付串行；恢复不能覆盖尚未结束的选择，busy 只在所有已提交授权操作结束后解除。进程重启仍以私有配置和系统持久授权恢复。

## 入口与验证

当前入口用文字显示未选择、目录授权／书库待验证、只读、需重新授权及不支持提供方；失败保留原选择并提供重新选择按钮。应用控制的按钮无 ripple／过渡，入口使用显式两页切换，无滚动；系统选择器不受应用动画约束。

JVM 测试使用授权元数据与配置适配器。`LocalDirectoryAuthorizationDeviceTest` 必须在通过真实系统选择器授权专用测试书库后单独运行；该测试重建 Activity 并撤销这次 debug 持久授权，不对源文件操作。不要将缺少准备数据的失败当成产品授权失败，也不要在通用无人值守设备测试中把它当成无需前置条件的用例。

实际命令、设备结果和待完成验收见 [第一阶段验证记录](../../../../../../../../../verification/phase-1.md)。
