# Android 工程

当前工程提供本地 SAF 目录授权入口、应用状态数据库、最小下载清单持久查询、应用完整副本读取和持久优先级任务队列。任务契约说明见 [任务模块](src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)。OneDrive 已接入个人账号浏览器授权和私有加密状态保存；真实登录验收状态见验证记录。队列与单执行协调器已实现，已注册本地快照导入和个人 OneDrive 浏览／快照导入处理器，由前台显式请求驱动，并提供候选任务控制入口；Calibre 完整导入与动态布尔栏目配置已实现；下载、后台驱动、完整任务页和阅读器尚未实现。

## 本地目录授权

点击“选择／重新授权目录”，在系统文件选择器中选择设备本地存储或 SD 卡目录。当前支持系统本地存储提供方；云端和未知提供方会被拒绝。取消重选保留原选择，重启后恢复持久授权；只读或失效时提示重新授权。

目录选择成功后保存当前候选位置与选择代号；取消保留原配置，重选保留旧书库绑定和副本。最小清单和副本读取已接入同一个应用状态库，但尚无实际下载或清单界面。

目录授权只表示平台权限已保存。点击“验证／同步元数据”才会提交高优先级候选任务，通过 SAF 只读获取根目录 `metadata.db`，检查内容版本、事务日志、SQLite 完整性与 Calibre 结构，再完整导入并激活书库。源发生变化、存在非空事务日志、目录缺失、权限失效或结构不兼容时显示明确错误，保留之前有效的导入。
加载可以暂停、继续、取消或失败后重试；继续与进程恢复均重新获取源，不复用未验证的暂存。取消后使用“验证／同步元数据”创建当前选择的新请求。重启只恢复私有任务状态，未完成排队任务须显式执行；不会自动同步。只读授权也可加载，但不意味着具有安全写回能力。通过“上一页／下一页”查看授权入口。

## 导入与栏目配置

当前入口为六个显式页面：本地授权／同步、OneDrive 授权、目录选择、OneDrive 同步、当前书库统计、已读栏目设置。同步成功后显示书籍／格式数量和上次成功同步时间；重新打开应用仅恢复私有导入，不等待网络或自动同步。再次选择已导入的位置可复用其最后有效缓存，新验证发现书库 UUID 或原数字 ID 对应的书籍 UUID 冲突时隔离为新的书库身份，旧缓存保持独立。

已读栏目页只列出导入发现的布尔栏目，按显示名称与动态 lookup 名选择并保存。未配置或栏目改名、删除、类型变化时明确显示配置问题；源空／否在有效配置下为未读，是为已读。本步骤没有源写回按钮，也不保存本地阅读状态覆盖。完整图书馆、搜索和分类界面留后续阶段。解析和发布契约见 [元数据模块](src/main/java/io/github/chenxiex/calibrecloud/metadata/AGENTS.md)。

## 构建

使用 JDK 21、Android SDK 36 和 Build Tools 36.0.0；最低支持 Android 11 / API 30。环境与设备连接见 [开发容器说明](../.github/.devcontainer/README.md)。

在项目根目录运行：

```bash
./gradlew --version
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
```

默认资源参数见 [gradle.properties](../gradle.properties)：worker 上限 4、JVM 可用处理器数 4、堆上限 2 GiB。临时降低资源占用：

```bash
./gradlew :app:assembleDebug --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2g -XX:ActiveProcessorCount=1 -Dfile.encoding=UTF-8'
```

依赖版本见 [版本目录](../gradle/libs.versions.toml)，实际解析版本见 [依赖锁](gradle.lockfile)。

## 构建产物

| 变体 | application ID | 名称 | APK |
| --- | --- | --- | --- |
| release | `io.github.chenxiex.calibrecloud` | Calibre Cloud | `build/outputs/apk/release/app-release-unsigned.apk` |
| debug | `io.github.chenxiex.calibrecloud.debug` | Calibre Cloud Debug | `build/outputs/apk/debug/app-debug.apk` |
| debug AndroidTest | `io.github.chenxiex.calibrecloud.debug.test` | 测试包 | `build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |

release 产物未签名。debug 使用独立包名和应用数据目录，可与正式版共存。

## OneDrive 配置

工程读取根目录被忽略的 `local.properties`，保留 `sdk.dir` 等已有属性。微软注册步骤与无凭据模板见 [OneDrive 应用注册](../README.md#onedrive-应用注册)。三项属性全部填写后才启用配置：

| 属性 | 用途 |
| --- | --- |
| `onedrive.clientId` | 两变体共用的开发者注册标识 |
| `onedrive.redirectUri` | release 完整回调 URI |
| `onedrive.debugRedirectUri` | debug 完整回调 URI |

正式版和 debug 版推荐使用各自独立的小写自定义 scheme，并与微软注册的 URI 完全一致；不需要 client secret。

缺少任一项时仍可构建和使用本地授权，OneDrive 页面显示配置指引。完整配置非法或回调范围重叠时，按构建错误中指出的属性修正。配置有效后点击“登录／重新登录”，在系统浏览器中使用个人微软账号授权。返回应用后显示授权结果；返回时未完成授权，可点击“取消登录”再重试。请求十分钟后失效。已授权时可显式刷新授权；凭据恢复失败会要求重新登录。

“个人 OneDrive 已授权”只表示 OAuth 授权完成。通过页面按钮进入目录页，点击“浏览／切换到 OneDrive”切换当前候选后端（旧书库缓存保留），浏览本人云盘；进入目录时一次完整加载子项目（Graph 返回分页时在同一任务中取完），随后上一页／下一页仅切换本地列表。当前六页入口是第二阶段临时验证 UI，每页三个目录用于验证分页；最终目录选择界面在第三阶段实现，不固定为三个目录。使用返回上层导航切换目录。在专用书库目录内点击“选择当前目录为书库”，再到任务页显式“验证／同步元数据”。快照在同一任务中通过 Calibre 结构验证并完整导入后激活书库，结果见当前书库页。任务页提供当前阶段支持的暂停、取消、继续及重试；限流或网络等待到期后需显式执行，后台运行尚未接入。重新打开页面只读私有配置和任务，不自动访问 Graph。

源路径仅在选定根内逐级解析，只读请求检查文件内容 cTag 与事务日志；无法确认一致性时保留旧快照并提示错误。网络／授权失败不会把源标为删除；预签名内容链接只用于当前传输。文件后端约束见 [OneDrive 后端](src/main/java/io/github/chenxiex/calibrecloud/storage/onedrive/AGENTS.md)，本步证据与待验项目见 [第二阶段记录](verification/phase-2.md)。令牌和进行中的登录请求通过 Android Keystore 加密保存，重启可恢复，不进入书籍 provider、普通缓存或备份。

从根目录验证配置矩阵：

```bash
python3 scripts/verify-oauth-config.py
```

结果与日志保存在被忽略的 `.oauth-verification/` 中。配置校验、回调匹配和授权实现约束见 [授权模块 AGENTS.md](src/main/java/io/github/chenxiex/calibrecloud/auth/AGENTS.md)。

构建结果、产物检查及未完成的验收见 [第一阶段验证记录](verification/phase-1.md)。应用状态与最小清单的实现及验证见 [第二阶段验证记录](verification/phase-2.md) 和 [状态库约束](src/main/java/io/github/chenxiex/calibrecloud/state/AGENTS.md)。面向 agent 的模块开发约束见 [AGENTS.md](AGENTS.md)。
