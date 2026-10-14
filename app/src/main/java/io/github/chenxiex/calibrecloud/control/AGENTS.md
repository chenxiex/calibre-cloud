# 外部控制接口约束

适用于 `control/`，实现 [R37](../../../../../../../../../spec.md#r37--外部阅读器控制接口)（Q84–Q92），同时遵循 [Android 模块约束](../../../../../../../../AGENTS.md)。KOReader 插件见 [external/koreader](../../../../../../../../../external/koreader/AGENTS.md)。

- 请求格式由 [ExternalProtocol](ExternalRequests.kt) 定义：`<scheme>://<操作>?token=…&file=…&name=…`，操作为 `mark_read`、`mark_unread`、`register`。新增操作只能加新名称，不改变已有操作的参数和语义；未知操作和缺少／格式不对的参数都拒绝。`scheme` 只是 Activity 入口的过滤器，正式版为 `calibrecloud`、debug 为 `calibrecloud-debug`（`build.gradle.kts` 的 `externalControlScheme`），不能与使用 application ID 的 OAuth 回调重叠。
- 两个入口共用 [ExternalControl](ExternalControl.kt)：`ExternalControlReceiver` 只接受显式广播的已读／未读请求，不切换界面；`ExternalControlActivity` 处理需要前台的请求（打开本应用、注册确认）。Android 10 起后台组件不能自行切到前台，所以“打开本应用”只能由前台阅读器经 Activity 发出（Q91）。Activity 使用空 `taskAffinity` 和透明主题：KOReader 等以新任务启动它时，结束后回到阅读器而不是本应用的图书馆。
- 只有开关开启且令牌已登记才执行；书籍只从当前书库的打开记录（`LastOpenedRepository.opened`，由打开阅读器成功时写入）按显示名查找，其它书库的记录只用于给出“属于另一个书库”的原因。接受的请求经 `ReadStatusService.submit` 提交，与图书馆页按钮相同，不另建已读数据、不访问源。
- 登记只保存令牌的 SHA-256，同名或同令牌再次登记时替换；确认登记时开关关闭则一并开启（Q92）。确认后若缺少通知权限即请求，因为拒绝只以 `ExternalNotifications` 的可替换通知告知（Q88），而请求到达时用户通常在阅读器中，无法再请求权限。
- 日志只写结果（如 `Rejected(reason=…)`），`ExternalRequest.toString()` 不含令牌和文件名；令牌也不能出现在通知中（R33）。

验证：JVM 的 `ExternalControlTest` 覆盖解析、开关／登记、打开记录匹配和提交拒绝的映射；平台测试 `ExternalControlRepositoryTest`、`LastOpenedRepositoryTest` 覆盖真实 SQLite 与 v15 迁移。真机结果见 [外部控制验证记录](../../../../../../../../verification/external-control.md)。
