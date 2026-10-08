# 私有书籍文件与 provider 开发约束

本文件适用于 `files/`；同时遵循 [Android 模块约束](../../../../../../../../AGENTS.md)。相关 Manifest、provider XML 和平台测试的修改也应检查本文件；需求以 [spec.md](../../../../../../../../../spec.md) 的 R06、R28、R32 为准。

## 文件读取与发布

`PrivateBookFiles` 只读取 `filesDir/books/<LibraryId>/<fileGeneration>.book`。磁盘名使用不透明 UUID，不使用书名、源路径或数字书籍 ID。文件工厂不能访问源库或创建缺失副本；拒绝符号链接、目录、空文件和已知长度不符。文件丢失返回缺失，权限和其它 I/O 错误分别映射为结构化失败，不携带异常全文。

长度校验不能替代加载阶段的格式完整性验证；大小未知时仍须在发布完整记录前完成验证。文件代次不可原地改写或重用；更新与清理必须协调已打开句柄，避免读者得到半文件。读取与句柄交付规则见 [存储约束](../storage/AGENTS.md)。

## 书籍 provider

`BookFileProvider` 是自定义 ContentProvider（R28、Q48），authority 为 `${applicationId}.books`，非导出、只通过单个 URI 的临时授权访问；`attachInfo` 拒绝导出或未开启授权的声明。数据库使用独立数据库目录，封面、暂存和凭据不得存入 `books`。

- URI 为 `content://<authority>/<书名-书籍ID.后缀>?copy=<LibraryId>:<文件代次>`。只有 `copy` 参数（两个 UUID 的规范写法、且没有其它参数或路径段）定位副本；路径段只是给阅读器用的名称，从不用来找文件。部分阅读器（如 PA6 的汉王阅读器）把 URI 路径当作导入文件名和书架标题，因此路径由 `BookFileNames.uriName` 生成：书名在前，后接书籍 ID 和与格式一致的后缀；空标题为资源 `book_untitled_name` 加后缀。同一本书更新后路径不变，不同书库中书籍 ID 与书名都相同时阅读器可能视为同一文件（已接受）。
- 只提供下载清单中仍发布的代次：查找与打开在共享 `copyAccess` 下进行，磁盘路径只由两个 UUID 拼出，以 `O_NOFOLLOW` 打开并拒绝链接目录、非普通文件、空文件和长度不符；已被更新或清理回收的代次、崩溃遗留文件都不提供，已打开描述符的阅读器继续读完旧字节。
- 显示名、大小与 MIME 均取自清单记录：显示名与 URI 路径同由 `BookFileNames.readerName` 生成“书名-书籍ID.后缀名”（KOReader 按显示名、汉王阅读器按路径导入，二者都须带 ID 以免同名书互相覆盖；控制字符改空格、不可见格式字符删除、路径分隔符改下划线，空标题用 `book_untitled_name`，按 UTF-8 255 字节截断）；MIME 先按常见 Calibre 格式映射，再用系统扩展名映射，最后为 `application/octet-stream`，不做格式转换。只接受 `"r"` 模式，`delete`／`update`／`insert` 一律拒绝，即使调用方是本应用。
- 打开前由界面经副本读取契约确认完整副本，再以 `BookUris.viewIntent` 发出 `ACTION_VIEW`，只带 `FLAG_GRANT_READ_URI_PERMISSION`，不授予写、持久或前缀权限，不传递 `file://`、源 SAF URI 或 OneDrive 链接。授权随本应用被强停（如 `am instrument` 结束）而撤销，设备探针需在阅读器读完前保持应用存活。

生产副本处理器先在 `filesDir/book-staging/<TaskId>/` 完整传输并校验，生成路径各组件拒绝符号链接，暂存清理不跟随目录内的链接，再重命名为不可变 books 代次并同步相关目录；清单和任务成功在同一 SQLite 事务发布。数据库未引用的崩溃遗留代次在后续传输时回收；文件工厂保留活跃句柄的旧代次直到关闭。普通读取器和清单更新必须共享状态库的 `copyAccess`，不能另建独立读取门。

精确格式清理先在状态事务删除匹配清单并持久保存 retire 代次，再到共享执行安全边界调用文件工厂回收；不能扫描当前库整个 books 目录来替代冻结格式范围。清元数据不回收任何书籍代次。其它库完整清理可回收相应 LibraryId 的无引用文件，保留当前库。已打开句柄继续读完整旧字节，最后关闭时才删除；无活跃读者时删除失败须明确失败，不能移除清理 journal 并声称空间已释放。持久恢复和允许删除的缓存根见[缓存维护](../storage/cache/CacheMaintenance.kt)，保护资料不在普通删除范围。

## 验证

`app/src/androidTest` 的 `BookFileProviderTest` 以真实 SQLite 清单和私有文件覆盖 URI 路径与显示名／大小／MIME、同名书不冲突、只按 `copy` 参数定位且拒绝各种伪造 URI、拒绝写入、回收代次与长度不符不提供、链接替换不提供、只读意图标志及 debug authority 隔离；显示名与 MIME 规则另有 JVM 测试。这些测试不能替代真实阅读器验收；执行与卸载规则见模块约束，实际结果记录于 [第一阶段验证记录](../../../../../../../../verification/phase-1.md) 与 [第三阶段验证记录](../../../../../../../../verification/phase-3.md)。
