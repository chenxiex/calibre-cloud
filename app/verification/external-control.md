# 外部控制接口验证记录

范围为 [R37](../../spec.md#r37--外部阅读器控制接口) 与 AC11（Q84–Q96）：应用侧的两个导出入口、登记、打开记录匹配和拒绝通知，以及 [KOReader 插件](../../external/koreader/AGENTS.md)。本记录只保存本轮实际执行的检查；未执行或未通过的项单列在末尾。

## 被验证的版本

基于提交 `7e5ae41`（`versionName` 1.1.0）的未提交改动。设备 PA6（Android 14，经宿主机 ADB server 连接）；debug application ID 经 `aapt2 dump packagename` 核对为 `io.github.chenxiex.calibrecloud.debug`，测试包为 `.debug.test`。阅读器为设备上已安装的 KOReader v2026.07.1（`org.koreader.launcher.fdroid`）。设备上同时装有正式版，测试中未安装、覆盖或调用它。

## 自动检查（2026-10-14）

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease :app:assembleDebug
./gradlew :app:assembleDebugAndroidTest
adb shell am instrument -w -e class <下列测试类> io.github.chenxiex.calibrecloud.debug.test/io.github.chenxiex.calibrecloud.AwakeTestRunner
```

- 构建：`BUILD SUCCESSFUL`，不带 `--write-locks`，`app/gradle.lockfile` 与 `settings-gradle.lockfile` 的 MD5 前后不变。
- JVM：**183 tests、0 failures、0 errors**，其中新增 `ExternalControlTest` 7 项（请求解析、令牌与文件名不进入 `toString`、开关与登记把关、按当前书库打开记录匹配、提交拒绝原因映射、同名重新登记替换令牌）。
- lint：debug／release 各 **0 errors**。新增 warning 只有 `ExportedReceiver`（R37 有意导出，由开关与登记令牌把关）；初次运行时 `MainActivity` 的 `AutoboxingStateCreation` 已改为 `mutableIntStateOf` 并复跑消除，其余为既有的版本提示。该改动在真机测试之后，只影响状态的装箱方式。

- 产物：debug 的合并 Manifest 中 `ExternalControlReceiver`（无 intent-filter，仅显式广播）与 `ExternalControlActivity`（`VIEW`，scheme `calibrecloud-debug`）为导出，书籍 provider 仍不导出；新 scheme 与使用 application ID 的 OAuth 回调 scheme 不重叠。
- 设备测试（手动安装两个 APK 后 `am instrument`，runner 为 `AwakeTestRunner`）：`ExternalControlRepositoryTest`、`LastOpenedRepositoryTest`（含打开记录按显示名查找与 v15→v16 迁移）、`SearchHistoryRepositoryTest`、`ApplicationStateRepositoryTest`、`StartupSyncTest`、`TaskSchemaMigrationTest`、`CoverTaskHandlerTest`、`CacheMaintenanceTest` 共 **OK (69 tests)**。其余设备测试本轮未运行。

## 真机端到端（PA6，2026-10-14）

测试书库为用户授权写入的 `/sdcard/Download/calibre-step04-test-library`（286 本）。开始前拉回 `metadata.db` 备份（SHA-256 `ff09b715…`），结束后推回并核对哈希一致。debug 包添加该书库（系统选择器面包屑 `PA6 › Download › calibre-step04-test-library`，确认框写明 Calibre Cloud Debug），同步后选择 `#read_status`。测试书为 ID 9 “The Quiet Harbor 4”（原值 0）。插件复制到 `/sdcard/koreader/plugins/calibrecloud.koplugin`（事先确认不存在），在菜单“目标应用”中先选 Calibre Cloud Debug。KOReader 界面无无障碍树，其中的操作由主 agent 按截图坐标执行；本应用页面用 device helper 按资源 ID 执行。

| 检查 | 实际结果 |
| --- | --- |
| 插件加载 | KOReader“工具”第 2 页出现“Calibre Cloud”，子菜单为中文；默认“标为已读后：打开 Calibre Cloud”“标为未读后：停留在 KOReader”；两版本都安装时显示“目标应用” |
| 打开记录 | debug 包打开书 9 后，KOReader 导入为 `The Quiet Harbor 4-9.epub`，与记录的显示名一致 |
| 注册（开关关闭） | 确认框显示“KOReader”及“外部阅读器控制尚未开启，允许后将同时开启”；允许后状态库 `external_control = 1`，`external_clients` 一行，令牌只存 64 位哈希；日志只有 `registration confirmed` |
| 文件管理器设为“读完” | 请求 `Accepted`，debug 包切到图书馆页；`read_status` 变为 1，写回任务完成并紧接书库同步，`integrity_check` 为 `ok` |
| 文件管理器设为“在读” | 经广播 `Accepted`，焦点保持在 KOReader；`read_status` 变为显式 `0` |
| 书末“标记为读完”（`ReaderStatus.markBook`） | `Accepted`，切到 debug 包图书馆页；`read_status` 变为 1 |
| 阅读菜单手动“标为未读” | `Accepted`，留在 KOReader |
| 再次注册 | 确认框出现在 KOReader 之上，显示“同名应用已经登记”；取消后焦点回到 KOReader。允许后请求通知权限（确认框同样在 KOReader 之上），允许后回到 KOReader；登记哈希与插件保存的令牌哈希一致（只比较哈希） |
| 设置页 | “更多 → 外部阅读器控制”显示开关与“KOReader 登记于 …，点击移除”；关闭开关写入状态库 |
| 拒绝与通知（adb 模拟调用） | 用一次性令牌经 `am broadcast`／`am start` 调用：开关关闭 → `DISABLED`；缺少令牌 → `Malformed`；未知操作 `open_book` → `UnknownOperation`；未打开过的“李尔王-5.epub” → `NOT_OPENED`；移除登记后 → `UNREGISTERED`。每次都替换同一条通知，正文为对应原因（带文件名的以“文件名：原因”开头） |
| 链接入口登记与提交 | `am start -d calibrecloud-debug://register?...` 弹出确认框并在开关关闭时一并开启；以该令牌广播 `mark_unread` 留在设置页并写入 0，`VIEW` 链接 `mark_read` 切到图书馆页并写入 1 |
| 日志 | 自最后一次清空日志起（约 20:24），logcat 中两个令牌均无匹配，`ExternalControl` 日志不含文件名；系统 `START` 日志只显示 `calibrecloud-debug://register/...` |

用户上手验收（2026-10-14）：重新安装当前改动的 debug 包与插件后，用户在 PA6 上以真实手指操作完成添加测试书库、打开书籍、注册与标为已读／未读的主流程，结论为通过。其后已卸载 debug 包、删除插件目录，测试书库 `metadata.db` 推回备份并核对哈希为 `ff09b715…`。

本轮发现并修复的问题：

- 确认框最初进入本应用的任务，确认后回到本应用图书馆而不是 KOReader（KOReader 以新任务启动外部 Activity）。改为空 `taskAffinity` 后，确认与取消都回到 KOReader。
- 插件每次注册都生成新令牌，取消确认后旧登记仍在而插件已换令牌，请求被以 `UNREGISTERED` 拒绝。改为只生成一次令牌、此后注册重发同一令牌。
- debug 包从未获得通知权限，拒绝通知不显示，而请求到达时用户在阅读器中无法再请求。改为确认登记后请求通知权限（已写入 R37）。

## 标为已读后删除 KOReader 副本（Q93，2026-10-14）

基于提交 `c8c7cb1` 的未提交改动。离线检查：

| 检查 | 结果 |
| --- | --- |
| `luacheck --globals G_reader_settings --no-max-line-length` | 0 错误；5 条警告与改动前相同（未使用的循环变量、`_dir` 与 `self`） |
| 桩模块 harness（luajit，KOReader 模块按 v2026.07.1 源码的签名打桩，真实文件放在临时目录） | 16 项全部通过：选项关闭时不删；开启前弹出含导入目录的中文确认框，确认后才开启；`.sdr` 选项在副本选项关闭时禁用；文件管理器标为读完后删除导入目录中的副本、清除缓存、更新历史与收藏、保留 `.sdr` 并刷新列表；导入目录外同名文件保留；开启 `.sdr` 选项后同时删除；关闭选项无需确认；打开中的书在 `CloseDocument` 的下一轮才删除；关闭前改回未读则不删；手动菜单标为已读同样在关闭后删除 |

用户上手验收（2026-10-14）：重新构建的 debug 包（`aapt2` 核对为 `io.github.chenxiex.calibrecloud.debug`）与插件装到 PA6，开始前备份测试书库 `metadata.db`（`ff09b715…`）。用户在 KOReader 中选择 debug 包为目标、开启两个删除选项，以书 5《李尔王》（测试书）走完流程，结论为看起来正常。agent 随后经 ADB 核对：KOReader 设置中 `remove_copy`、`remove_sdr` 均为 `true`；导入目录 `/sdcard/Android/data/org.koreader.launcher.fdroid/files/` 中 `李尔王-5.epub` 与 `李尔王-5.sdr` 都已不存在，其它副本及 `.sdr` 未变；`.sdr` 位置为默认的书籍旁（`docsettings`、`hashdocsettings` 目录均不存在，没有其它位置的残留）；`history.lua` 仍有该书条目（未开启“自动移除已删除项”，按 KOReader 规则变灰）；测试书库中书 5 的 `read_status` 为 1。

用户随后关闭“同时删除阅读数据”（设置中 `remove_sdr = false`）重试：从 debug 包再次打开书 5（22:21 重新导入），在阅读中标为已读并切到 debug 包（22:22:30 测试书库发生写回提交，`read_status` 为 1）。此时 KOReader 仍打开该书，副本保留；用户回到 KOReader 打开另一本书后，`李尔王-5.epub` 被删除，`李尔王-5.sdr` 保留（关闭时写入的 `metadata.epub.lua` 与 `.old`），与“打开中的书关闭后删除、只删副本”一致。保留的 `.sdr` 中书籍状态为 `reading`，用户确认这次经阅读菜单“在 Calibre Cloud 中标为已读”发送（该入口不改变 KOReader 的书籍状态），并认为这一结果可以接受。

清理：测试书库 `metadata.db` 推回备份并核对哈希为 `ff09b715…`，已卸载 debug 包、删除插件目录，正式版未改动。保留项：KOReader 中的 `李尔王-5.sdr`、历史条目和 `settings.reader.lua` 中的 `calibrecloud` 设置项，属于用户的 KOReader 数据。

## 标为已读后退出 KOReader 并打开 Calibre Cloud（Q94–Q96，2026-10-14）

基于提交 `6c28d8a` 的未提交改动，只改插件；debug 包由该提交构建（`aapt2` 核对为 `io.github.chenxiex.calibrecloud.debug`）。退出流程参照 KOReader v2026.07.1 源码中的 `ReaderUI:onClose`、`FileManager:onClose`、`UIManager:quit` 与 Android `Device:exit`。

| 检查 | 结果 |
| --- | --- |
| `luacheck --no-global --no-max-line-length` | 0 错误；5 条警告与改动前相同 |
| 桩模块 harness（luajit） | 12 项通过：开关开启时手动标为已读先关闭书籍再 `quit`、不提示“已发送”；开关关闭时与标为未读时停留并提示；发送失败时提示且不退出；开启删除选项时正在阅读的书在退出前删除副本、保留 `.sdr`；不退出时仍在关闭后的下一轮删除；文件管理器设为读完时关闭文件管理器并退出 |

真机（PA6，测试书库 `/sdcard/Download/calibre-step04-test-library`，开始前备份 `metadata.db`，哈希 `ff09b715…`）：用户添加书库、注册并在 KOReader 中手动操作，agent 经 ADB 核对日志与书库。

- Q94 的中间版本（独立退出开关与打开／停留组合）：两种组合用户确认正常，返回不再回到 KOReader。书 5 与书 1 写回为 1；KOReader 日志依次为删除导入副本、`Preparing to quit UIManager`、`Stopping KOReader main activity`。用户随后指出“停留 + 退出”文案矛盾，改为 Q95 的方案。
- Q95／Q96 的最终版本：开关开启时书 288 标为已读经 Activity 入口 `Accepted`，删除副本后 KOReader 退出，切到 debug 包；开关关闭时的标为已读及标为未读都只经广播 `Accepted`（无 Activity 启动），留在 KOReader。用户确认两个结果符合预期。
- 书库：两轮只有被测书籍的 `read_status` 改变，`integrity_check` 为 `ok`。logcat 中含 `token=` 的行都来自系统窗口管理，没有插件令牌或请求 URI。

清理：测试书库 `metadata.db` 推回备份并核对哈希为 `ff09b715…`，已卸载 debug 包、删除插件目录，正式版未改动。保留项：KOReader `settings.reader.lua` 的 `calibrecloud` 设置（含已不再读取的 `open_on_read`、`exit_on_read`）、导入目录中其它书的 `.sdr`，属于用户的 KOReader 数据。应用户要求，已强停 KOReader 后删除导入目录中的 `李尔王-5.epub` 与 `李尔王-5.sdr`（只含 `metadata.epub.lua` 及 `.old`）。

未执行：书末提示与书籍状态页设为“读完”时的退出（自动发送路径只经桩测试覆盖）、多本书同时设为读完时的退出。

## 应用内安装 KOReader 插件（Q97、Q98，2026-10-27）

基于提交 `8663baf` 的未提交改动；debug 包 `aapt2` 核对为 `io.github.chenxiex.calibrecloud.debug`。

| 检查 | 结果 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` | 通过；新增 `KoreaderPluginInstallerTest` 9 项通过（写入顺序与覆盖、缺少 `plugins` 时创建、非 KOReader 目录拒绝且不写入并释放授权、版本状态、启动检查每进程一次、授权／目录失效、写入失败、替换目录时的授权释放） |
| 真机 `am instrument -e package io.github.chenxiex.calibrecloud.state` | 24 项通过（含 v16→v17 迁移） |
| 真机 `TaskSchemaMigrationTest`、`CacheMaintenanceTest`、`LibrariesPageTest` | 28 项通过；`LibrariesPageTest` 首轮 3 项因屏幕处于 Dozing 报 “No compose hierarchies found”，唤醒屏幕后重跑通过 |

真机（PA6，KOReader F-Droid 版，`/sdcard/koreader/plugins/calibrecloud.koplugin` 已有用户安装的 1.2.0，与仓库文件哈希相同；测试前拉取备份）：

- 点击“安装插件”后系统选择器直接位于 `PA6 › koreader`（`EXTRA_INITIAL_URI` 生效）。
- 选择 `koreader/plugins` 并允许：显示“不是 KOReader 数据目录”，插件文件时间未变；`dumpsys activity permissions` 中该 tree 的 `persisted=0x0`，持久授权已释放。
- 选择 `koreader` 并允许：显示“已安装 1.2.0”和重启提示，两个文件被覆盖（时间更新、哈希与仓库一致），没有出现重命名的副本。
- 删除本轮写出的插件目录后点击“重新安装插件”：不经选择器，重新创建目录与 `main.lua`、`_meta.lua`，名称与哈希正确。
- 把 `_meta.lua` 改为 1.1.0、`pm grant` 通知权限后冷启动：发出通知“KOReader 插件需要更新”（渠道 `koreader-plugin`，ID 14）；点击通知进入外部阅读器控制页，显示“需要更新：已安装 1.1.0，本应用附带 1.2.0”。再次冷启动重新发出通知后点击“更新插件”：通知撤回，文件恢复为 1.2.0 的哈希；插件为最新时冷启动不发通知。
- 用户查看 UI 后要求把安装按钮改为行尾图标：改为 `MenuEntry.Actions` 一行后重新安装 debug 包，截图核对“已安装 1.2.0”行尾显示刷新与文件夹图标。
- 首轮真机发现返回页面时崩溃（`pluginEntries` 作为独立可重组函数向已完成的 `buildList` 追加），已改为在页面的 `buildList` 中内联后重测通过。

清理：已卸载 debug 与测试包；KOReader 插件目录最终内容与测试前备份哈希一致。未执行：未重启 KOReader 核对应用写出的插件被加载（文件与此前手动安装并真机验收的插件逐字节相同、路径相同）；未在其它设备或其它 KOReader 安装路径上验证选择器的初始位置。

## 观察与未完成项

- Q93 真机上未核对：导入目录外同名文件保留、关闭前改回未读取消删除，以及经书末提示／书籍状态页设为“已完成”时的删除。

- 从其它应用回到 KOReader 后，PA6 上紧接着的几次 `input tap` 偶尔不被 KOReader 处理：一次在重装 debug 包后持续约 2 分钟无响应（日志有 `cannot blit: no window`），只能强停 KOReader；另一次稍后的点击恢复正常。期间 KOReader 的 Lua 线程处于休眠而不是忙于插件代码。这是否影响真实手指操作需用户体验确认。
- 未在真机上执行：阅读中“书籍状态”页（`BookStatusWidget.onChangeBookStatus`）的切换、拒绝通知的点击跳转、“属于另一个书库”的拒绝（只有 JVM 测试）、多本书同时改状态。
- 本轮未运行完整设备测试套件和 Gradle `connectedDebugAndroidTest`。
- 设备清理：已卸载 debug 与测试包，删除自建的插件目录，测试书库已恢复原哈希。保留项：KOReader 导入的 `The Quiet Harbor 4-9.epub` 与同名 `.sdr`、KOReader 历史中的该书，以及 `settings.reader.lua` 中的 `calibrecloud` 设置项（含已无对应应用的 debug 令牌）；这些属于用户的 KOReader 数据，由用户决定是否删除。
