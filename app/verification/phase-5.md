# 第五阶段验证记录

范围以 [spec.md](../../spec.md) 实施顺序第 5 阶段“首版验收”为准：功能串联、阶段结果汇总、日志与依赖许可信息，验收重点 AC01–AC10，未完成的真机项不宣称通过。本记录只保存本阶段实际执行的检查和对前四阶段证据的汇总；各项细节以对应阶段记录为准，不在此复制。

## 被验证的版本

提交 `d815ccf`（`versionName` 0.2.0、`versionCode` 2）。第四阶段步骤 07 验收后的生产代码改动只有 `26556c0` 的版本号；`d815ccf` 只改开发容器的 ADB server 设置。设备 PA6（Android 14，USB 经宿主机 ADB server 连接），debug application ID 经 `aapt2 dump packagename` 核对为 `io.github.chenxiex.calibrecloud.debug`，测试包为 `.debug.test`。

## 自动检查（2026-10-11）

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r io.github.chenxiex.calibrecloud.debug.test/io.github.chenxiex.calibrecloud.AwakeTestRunner
```

- 构建：`BUILD SUCCESSFUL in 2m 37s`。不带 `--write-locks` 运行，`app/gradle.lockfile` 与 `settings-gradle.lockfile` 的 MD5 前后不变。
- JVM：**173 tests、0 failures、0 errors**（含 `ThirdPartyNoticesTest`：第三方声明覆盖 release 运行时依赖锁中全部模块，许可证均在 GPL-3.0 兼容清单中且有全文资源）。
- lint：debug／release 各 **0 errors**，5 条 warning 均为 `AndroidGradlePluginVersion`／`NewerVersionAvailable` 版本提示。
- 设备测试：`./gradlew :app:connectedDebugAndroidTest` 未能执行。开发容器改为通过 `ADB_SERVER_SOCKET` 使用宿主机 ADB server 后，Gradle 的 ddmlib 仍连接容器内 `localhost:5037`，报“Cannot reach ADB server”且没有安装或运行任何测试；已终止该次运行。改为手动安装两个 APK 后用 `am instrument` 运行同一套件：**OK (316 tests)**，0 失败，28 项为需要显式参数的源写入／真实上游测试（assumption 跳过，不计为通过），282.8 秒。ddmlib 32.1.1 的 ADB server 地址固定为回环地址、只有端口可经 `ANDROID_ADB_SERVER_PORT` 配置；随后用 `socat` 把容器内 `127.0.0.1:5037` 转发到宿主机 ADB server，`connectedDebugAndroidTest` 单类运行 `LastOpenedRepositoryTest` 2 项通过，结束后 AGP 自动卸载 debug 与测试包。转发方法已写入[开发容器说明](../../.github/.devcontainer/README.md#真机调试)。
- 产物：debug 与 release 的 minSdk 30、targetSdk 36；debug 可调试，provider authority 为独立的 `io.github.chenxiex.calibrecloud.debug.books` 且不导出。release 已用 `local.properties` 中配置的发布密钥签名（`apksigner verify` 通过，未在设备上安装），权限只有 `INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK`、`RECEIVE_BOOT_COMPLETED`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS` 及 AndroidX 生成的非导出接收器权限，没有广泛存储权限。

## 静态审计

- 日志（R33、AC09）：生产代码共 12 处 `android.util.Log` 调用，分布在 `QueueWorker`、`BackgroundTasks`、`OneDriveAuthorization`、OneDrive 源诊断、写回处理器、元数据与封面缓存清理。消息只含任务 ID、书库 ID、阶段代码、状态类名、字节数、重试次数、耗时和错误分类枚举；没有书名、作者、搜索词、路径、URI、令牌或异常原文。进度和授权调试日志仅在 debug 构建输出，release 只在失败时输出 warning；WorkManager 日志级别为 WARN。
- 文案（R36、AC10）：`rg '[\p{Han}]'` 在生产 Kotlin 中除注释外无匹配；没有 `Text("…")`、字面量 `contentDescription`、布局或 Manifest 中的字面文字；通知标题、正文和渠道名均取自资源。资源只在无语言限定的 `values/`（共 391 个字符串／数量资源），未翻译的设备语言回退中文。业务层以枚举状态传给展示层，没有发现以译文作判断或持久化的路径。
- 依赖许可（R30）：[第三方声明](../src/main/res/raw/third_party_notices.txt)按组件列出版权、许可证与模块，由上述 JVM 测试与依赖锁对齐；本次依赖未变化，未修改声明。

## 本地书库功能串联（PA6，2026-10-11）

在 `pm clear` 后的 debug 包上一次连续执行，测试书库为用户授权写入的 `/sdcard/Download/calibre-step04-test-library`（286 本）。开始前记录 620 个文件 MD5，`metadata.db` 为 `e12b36d4…`（与第四阶段写前原始值相同），并拉回备份。页面操作用 [android-device-verification](../../.agents/skills/android-device-verification/SKILL.md) helper 按资源 ID 执行，复用第四阶段步骤 06 的添加流程。

| 环节 | 实际结果 |
| --- | --- |
| 添加与同步（R07–R09） | 空状态“打开设置” → 添加书库 → 本地目录；系统选择器面包屑核对为 `PA6 › Download › calibre-step04-test-library`，确认框写明 Calibre Cloud Debug 与该目录，允许后回到向导第 3 步，“完成”后自动同步并显示书籍；选择 `#read_status` 为已读栏目。整段约 83 秒 |
| 打开（R28–R29） | 短按书 1：高优先级下载后直接交给默认 EPUB 阅读器 KOReader（系统日志 `VIEW`、`application/epub+zip`、临时读权限标志）；私有副本 MD5 与源 EPUB 相同（`2d9e1d1c…`）；底栏出现继续阅读“Quick Start Guide” |
| 标为已读（R13–R16、R26–R27） | 选中书 1，菜单只有“标为已读”；点击后立即出现空心待写入斜幅，随后消失并显示实心斜幅（实心 4 → 5）。源库差异（`calibre_db_diff.py`）只有书 1 的 `custom_column_1` 新行值 1、该书 `last_modified`、`metadata_dirtied` 与序号；`integrity_check` 为 `ok`；目录内无 `metadata.db.calibrecloud-*` 残留 |
| 标为未读 | 再次选中书 1，菜单改为“标为未读”；待写入斜幅出现后消失，实心回到 4；源库书 1 的值为显式 `0`（不是空值），`integrity_check` 为 `ok` |
| 任务队列（R18） | “进行中”为空；“已完成”依次为下载书籍、已读状态写回（1 本）“已写入书库”、元数据同步、检查更新，两轮写回各一组 |
| 清除元数据（R12） | 确认页范围“1 个书库，0 个书籍副本，可释放 1.0 MB 应用空间”，确认后“清理完成”；图书馆显示“尚无完整元数据……已下载的书籍副本仍然保留”及“同步元数据”“已下载文件”入口，底栏继续阅读保留 |
| 无元数据打开与移除 | “已下载文件”列出“Quick Start Guide／EPUB、52 kB、源状态：尚未确认”，点“打开”交给 KOReader；“移除此格式”确认页“格式：EPUB／将删除 1 个应用内副本，共 52 kB”，确认后“副本移除完成”，应用私有 `files/books` 下文件数为 0 |
| 重新同步 | “更多 → 立即同步元数据”后显示“上次成功同步：2026/10/11 11:21” |
| 关于（R30） | 显示“版本 0.2.0-debug”、GPL-3.0-or-later 说明与另行授权说明、“GPL 全文”与“第三方声明”入口；第三方声明分 9 页，首页为 AndroidX、Apache-2.0 及模块列表 |
| 日志 | 应用 uid 的 logcat 不含 `http`、`content://`、`/sdcard`、`/storage`、token、书名、作者或测试目录名。logcat 在打开已下载副本前清空过一次，本次审计只覆盖其后的 32 行（重新同步部分）；写回日志的同类审计沿用第四阶段步骤 07 |

收尾：推回备份的 `metadata.db`，620 个文件 MD5 与开始前完全一致。KOReader 本次导入的 `Quick Start Guide-1.epub`（MD5 与源相同、时间为本次打开）及其 `.sdr` 目录已删除；KOReader 自身的历史与统计未清理。卸载 debug 与测试包均 `Success`，设备上只剩正式应用（未触碰）。产物在被忽略的 `app/build/verification/phase5/`。

本次串联没有重复以下已通过且未受改动影响的路径：搜索／筛选／分类、批量下载、离线写回合并、写后同步失败、推送阶段进程终止、栏目失效、书库切换隔离、后台与重启恢复。

## OneDrive

本阶段没有重新登录 OneDrive，OneDrive 串联沿用[第三阶段步骤 09](phase-3.md#步骤-09第三阶段端到端联验2026-10-09)（同步、浏览、搜索、下载打开、批量与移除、清元数据后打开、书库切换隔离）与[第四阶段步骤 07](phase-4.md#步骤-07两后端端到端联验与收口2026-10-11)（写回、写后同步失败、推送阶段进程终止、离线合并、两书库同 ID）的真实服务证据。沿用依据是此后生产代码只改了版本号。

## AC01–AC10 汇总

“通过”表示对应阶段已有自动与真机／真实服务证据并经用户共同验收；括号内为保留的条件项，详见下节。

| 验收 | 结论 | 主要证据 |
| --- | --- | --- |
| AC01 | 通过 | minSdk 30 产物核对（本阶段）；本地与个人 OneDrive 两后端（第二、三阶段）；两份样本书库隔离及同数字 ID 书库（[第三阶段步骤 09](phase-3.md)、[第四阶段步骤 07](phase-4.md)） |
| AC02 | 通过 | 契约测试；真实 OneDrive 请求计数：未变化同步 1 个请求、下载与封面各 1 个 Graph 请求、已有副本打开 0 个请求，无 `children`（[第三阶段步骤 05](phase-3.md)） |
| AC03 | 通过（SD 卡与云端提供方选择器条件项未执行） | 两后端复制／下载、更新、源删除、清元数据、其它书库清理与孤立缓存（[第二阶段](phase-2.md)、第三阶段）；本阶段本地清元数据后副本保留与按格式移除 |
| AC04 | 通过 | [第四阶段](phase-4.md)两后端真实写入：已读／未读、冲突、推送中断后重跑、写后同步失败、Calibre 一致性；本阶段本地两目标复验 |
| AC05 | 通过（“排队”显示真机未构造；SIGKILL 后无前台时的即时自动恢复未证实） | 平台测试覆盖优先级、让出、去重提升、封面批次、依赖与恢复；真实后台、设备重启恢复、通知与启动同步（[第二阶段步骤 09](phase-2.md)）；进程终止后重新打开恢复（第四阶段步骤 07） |
| AC06 | 通过 | 查询路径测试、286 本扩展库真机浏览／搜索／筛选，墨水屏分页与灰度经用户验收（[第三阶段](phase-3.md)） |
| AC07 | 通过 | 多格式筛选、跨标签去重、冻结集合、按格式移除（第三阶段步骤 06）；标记按钮判定与写回提交（第四阶段步骤 06）；本阶段全已读时显示“标为未读” |
| AC08 | 通过（汉王 PDF 阅读器需真实路径，不能打开受控 URI，为已知限制） | 两后端真实文件交给 KOReader／汉王 hvXReader，显示名、无源目录权限、后台不跳转与无阅读器错误（第三阶段步骤 04、09）；本阶段本地打开复验 |
| AC09 | 通过（无浏览器登录、Activity 重建中的浏览器往返、真实服务端限流未实测） | 登录、登录失效与目录授权撤销等待、无网络、冲突、空间不足（接口注入）、日志脱敏、更多菜单（第一至四阶段）；本阶段日志静态与 logcat 审计 |
| AC10 | 通过 | 本阶段对全部已实现展示路径的静态审计；动态参数与 plurals 修正见第三阶段步骤 09 |

## 保留的条件项与未完成

以下项目在前序阶段已记录为未执行或仅有注入证据，本阶段没有新的设备或服务条件来补验，保持原状态：

- SD 卡目录与云端文档提供方的真实选择器场景（设备没有可挂载的 public volume，未安装云端提供方），只有自动拒绝测试（[第一阶段步骤 04](phase-1.md)）。
- OneDrive 无兼容浏览器时的登录、Activity 重建期间的真实浏览器往返（用户确认不阻塞，[第一阶段](phase-1.md)）。
- 真实服务端限流无法按需触发，“等待限流”只有接口回归；“排队”状态的真机显示未构造（[第二阶段](phase-2.md#尚未完成)）。
- SIGKILL 后在没有用户打开应用的情况下，系统即时自动恢复未证实；设备重启后的后台恢复与重新打开后的恢复已通过。
- 磁盘空间不足只在接口边界注入验证，未在真机填满存储。
- 任务历史保留 50 条在长期使用下的任务页观感未目视核对（[第三阶段](phase-3.md)）。
- 真机证据限 PA6（Android 14／API 34）；最低支持的 Android 11 只核对了构建配置。代表性书库为程序生成的 286 本扩展库（用户已在桌面 Calibre 确认可加载），不是用户的真实书库。

用户共同验收（2026-10-11）：确认本记录的汇总结论，上列条件项均难以构造测试，接受为首版已知限制，日后出现实际使用场景中的缺陷再排查。OneDrive 不在最终构建上重跑串联：两后端经同一存储接口接入上层，OneDrive 实现已有契约测试与真实服务证据，此后生产代码只改了版本号。

验收后清理：经用户同意删除了设备上两个非本阶段创建的遗留测试文件——`/data/local/tmp` 中 2026-10-09 的空 helper XML，以及 KOReader 私有目录中第三阶段留下的 `Return to Garden 280-285.sdr`（只含 `metadata.pdf.lua`）。KOReader 目录中的其它书籍未触碰。
