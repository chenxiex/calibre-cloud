# KOReader 插件

[calibrecloud.koplugin](calibrecloud.koplugin/main.lua) 实现 R37 的 KOReader 端（Q89–Q92），遵循 [外部适配约束](../AGENTS.md)。实测版本为 PA6 上的 KOReader v2026.07.1（F-Droid 包 `org.koreader.launcher.fdroid`）。

- 只在 Android 上工作，经 KOReader 导出的 `android.jni` 构造 Intent：停留用指定组件的显式广播，打开本应用和注册用指定包名的 `VIEW`。KOReader 声明了 `QUERY_ALL_PACKAGES`，因此能看到并启动本应用；JNI 调用后检查并清除 Java 异常，异常只记为发送失败。
- KOReader 没有书籍状态变化事件，插件在加载时一次性包装三处设置状态的函数：`ReaderStatus.markBook`（书末提示与自动标记）、`BookStatusWidget.onChangeBookStatus`（阅读中的书籍状态页）、`filemanagerutil.saveSummary`（文件管理器的状态按钮，旧状态取自 `BookList` 缓存）。进入或离开“已完成”才发送；同一时刻的多本书合并到下一轮，只有一个请求打开本应用。升级 KOReader 时核对这些函数仍存在且语义不变。
- 只发送文件名形如“书名-数字.后缀”的书，即本应用给出的显示名（R28）；其它书由本应用以“没有以此文件名打开的书”拒绝。
- 令牌在第一次注册时生成并保存在 `G_reader_settings` 的 `calibrecloud` 项，此后注册都重发同一令牌，因此取消某次注册确认不会让已有登记失效。设置项还有 `auto`、`open_and_exit`（默认关）和 `package`。
- 同时安装正式版与 debug 版时菜单显示“目标应用”，供开发测试选择 debug 包；只装一个时自动使用已安装的那个。测试时务必先选 debug 包，避免请求发往装有真实书库的正式应用。
- 删除副本（Q93）：`remove_copy`、`remove_sdr` 默认关闭，开启前以 `ConfirmBox` 说明删除内容。只删除实际路径直接位于 KOReader 导入目录（`android.app.activity.externalDataPath`，即 launcher 导入 `content://` 时用的 `getExternalFilesDir(null)`）中、名称符合显示名的文件，且只在请求成功交给系统后删除。删除仿照 v2026.07.1 的 `FileManager:deleteFile`：`os.remove`、`BookList.resetBookInfoCache`、`ReadHistory:fileDeleted`、`ReadCollection:removeItem`；`DocSettings.updateLocation(file)` 会删除 `.sdr`，只在 `remove_sdr` 时调用。打开中的书记在内存中，`CloseDocument` 后下一轮再删，因为 `ReaderUI:onClose` 在该事件之后还会写入 `.sdr`；KOReader 在关闭前退出则不删除。升级 KOReader 时核对这些函数与导入目录。
- 打开并退出（Q94、Q95）：标为未读总是走广播；标为已读在 `open_and_exit` 开启时经 Activity 打开本应用，多本书同时改状态时只有一本走 Activity，其余走广播。只在打开本应用的请求成功交给系统后退出，走 KOReader 自己的退出路径：下一轮关闭 `ReaderUI` 或 `FileManager`（保存进度与设置），再 `UIManager:quit()`，主循环结束后 `reader.lua` 调用 `Device:exit()`（Android 上为 `ANativeActivity_finish`）。`quit` 清空任务队列，所以退出时就地删除刚关闭的书的副本，不等下一轮。退出不依赖窗口栈为空，书籍状态页等弹窗仍打开时也能退出。
- 应用内安装（Q97、Q98）：应用随 APK 附带本目录的 `*.lua`，经 [KoreaderPluginInstaller](../../app/src/main/java/io/github/chenxiex/calibrecloud/control/KoreaderPlugin.kt) 写入用户选择的 KOReader 数据目录；状态只读 `_meta.lua` 的 `version = "…"` 行，因此保持该写法。
- 插件文案自带中文对照表（KOReader 的翻译目录没有这些字符串），其它语言显示英文原文。
