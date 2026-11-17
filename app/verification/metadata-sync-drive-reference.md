# OneDrive 元数据同步的 drive 引用校验回归

本轮对应 R08、R09、R19、R31，检查 AC03 的同步失败路径和 AC09 的错误分类。产品行为沿用 `spec.md`，没有新增源写入能力。

## 现场与根因（2026-11-17）

PA6 正式应用为 1.3.0（versionCode 6）。只读页面查询确认“上次同步失败：当前后端不支持该操作。原有效元数据保留。”用户说明同一 drive 的两个书库均失败，之前可正常登录和同步，最近才开始失败。

初次 logcat 没有保留失败诊断。用户再次手动同步后捕获到 `11-17 22:57:00.324 ... W OneDriveSource: stage=parent_drive`（设备日志时间）。该标签来自 `OneDriveSourceBackend.item` 的父级 drive 校验，而不是同步能力判断。

两个问题分别是：

1. 原实现将 `parentReference.driveId` 与保存的 drive ID 作严格字符串比较。同一个人网盘的 16 位十六进制 ID 在 Graph 响应与请求中可能仅大小写不同，因此有效快照被误拒绝。新配置的 debug 书库以当前表示读取成功；测试侧仅在请求内切换该 ID 的大小写，旧实现就在同一个真实账号／源上以 `parent_drive` 失败。修复后同一探针通过。
2. 该拒绝、HTTP 400 和 JSON 解析异常等被归为 `UNSUPPORTED_OPERATION`，界面遂错误地解释为“当前后端不支持该操作”。新增 `INVALID_SOURCE_RESPONSE`，经任务序列化与界面映射显示“存储服务响应异常，无法验证源数据”。真正的能力／支持范围拒绝仍使用原类别。

[官方大小写文档](https://learn.microsoft.com/en-us/onedrive/developer/rest-api/concepts/case-sensitivity)将资源 ID 定义为区分大小写；[上游第一手报告 #1902](https://github.com/OneDrive/onedrive-api-docs/issues/1902)记录了个人网盘各端点的 drive ID 大小写不一致。因此修复只兼容双方均为 ASCII 16 位十六进制时的大小写差异，其他不透明 ID 继续严格比较。不同十六进制值、跨 drive 和缺失 drive 字段仍拒绝。请求和持久化配置保留原值，不改变 account ID、根 item ID、书籍身份或 cTag，也不增加 Graph 请求。这让旧配置可以直接使用修复；同一 drive 的不同书库仍由各自根 item ID 隔离。

未导出正式应用的私有配置，因而没有直接比对正式配置中原始 ID 的字面值；上述归因依据正式失败标签、用户描述及同账号真实服务上的同类拒绝复现。正式安装包未升级，原两书库在修复版本上的完整用户流程仍待发布后确认。

## 探针范围与数据边界

用户登录独立 debug 应用后明确指定其中配置的是生产书库，并要求不得改动。本轮遵循该指示，只把该位置作为只读源：`OneDriveSnapshotReferenceDeviceTest` 仅调用生产后端的 `acquireSnapshot`，不执行写回、不修改配置、不驱动整个任务队列。它读取一次私有快照，验证长度及 SQLite 完整性；第二个大小写变体请求复用已观察到的 cTag，源未变时不再下载内容。该探针不代替完整元数据导入或写回验收。

测试须以 `oneDriveSnapshotReference=true` 显式启用，且检查独立 debug application ID。测试侧 interceptor 仅记录父引用存在性、ID 精确相等／仅大小写不同、双方是否为 16 位十六进制，不记录原始 ID、响应、URL 或令牌。JSON 探测解析失败使用不含原始异常 cause 的固定断言信息。快照临时目录在 `finally` 清理；会话结束后已卸载 debug 与测试包，ADB 包列表只剩正式应用。未修改或删除任何源文件。

原始日志、红绿测试输出和临时 APK 检查结果位于被忽略的 `app/build/verification/metadata-sync-bug/`，不提交原始产物。

## 本轮验证

| 层次 | 实际检查 | 结果与证据 |
| --- | --- | --- |
| TDD 红灯：错误分类 | `:app:testDebugUnitTest --tests '*OneDriveSourceBackendTest.snapshotResponseFailuresReportSourceResponseErrors'` | HTTP 400、无效 JSON、跨 drive、缺失 drive 四分支原先均误报 `UNSUPPORTED_OPERATION`；`response-error-red.txt` |
| TDD 红灯：drive 比较 | `:app:testDebugUnitTest --tests '*OneDriveDriveReferenceTest'`，修正 fixture 账号前缀后在严格比较实现上运行 | 同一 16hex ID 的大小写变体被拒；`drive-reference-red.txt`、`drive-reference-red.xml` |
| JVM 回归绿灯 | `:app:testDebugUnitTest --tests '*OneDriveDriveReferenceTest' --tests '*OneDriveSourceBackendTest'` | 40 个测试通过；覆盖大小写兼容、安全拒绝、原请求 ID 保留、请求次数及既有快照／下载／写回契约；`response-and-drive-green.txt` |
| 平台任务序列化／提示 | ADB instrumentation 运行 `TaskErrorPersistenceTest` | 新错误码编解码后保留原因并映射中文文案，1 项通过；`device-before-fix.txt` |
| 真实服务基线 | 旧 debug 实现读取当前配置的快照 | 1 项通过；安全诊断为 `exact=true caseOnly=false bothHex=true`；`device-before-fix.txt` |
| 真实服务红灯 | 旧 debug 实现读取同一源的两个请求 ID 表示 | 原表示成功，大小写变体失败；`exact=false caseOnly=true bothHex=true` 后紧接 `stage=parent_drive`；`device-reference-red.txt`、`reference-diagnostics-red.txt` |
| 真实服务绿灯 | 更新独立 debug 包后运行同一探针，保留其配置及授权 | 原表示及大小写变体均通过，1 项通过；`device-reference-green.txt`、`reference-diagnostics-green.txt` |
| 构建 | `:app:assembleDebug :app:assembleDebugAndroidTest`，最终探针变更后再运行 `:app:assembleDebugAndroidTest` | 均通过；`response-and-drive-green.txt`、`probe-variants-build.txt`。最终 `git diff --check` 通过 |
| 静态检查 | `:app:lintDebug` | 最终检查通过，0 error、6 warning（依赖版本提示及既有导出 receiver）；`lint-final.txt` |

本轮 Gradle 命令使用 `-Pkotlin.compiler.execution.strategy=in-process`。ADB 直接使用已连接设备的 server，没有通过 Gradle/ddmlib 运行设备测试，因此无需 localhost 转发。

## 构建过程中的失败与修正

初次把构建和单元测试并行运行，二者写入同一模块产物，生成的 APK 缺少 Application 类，安装后崩溃。已停止相关构建、保留验证产物并清理重建，随后串行构建；确认 APK 包含 Application 类，重新安装后进程和主界面检查通过。此问题属于本轮构建过程，不是正式应用同步失败的原因。
