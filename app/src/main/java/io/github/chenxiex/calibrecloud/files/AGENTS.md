# 私有书籍文件与 provider 开发约束

本文件适用于 `files/`；同时遵循 [Android 模块约束](../../../../../../../../AGENTS.md)。相关 Manifest、provider XML 和平台测试的修改也应检查本文件；需求以 [spec.md](../../../../../../../../../spec.md) 的 R06、R28、R32 为准。

## 文件读取与发布

`PrivateBookFiles` 只读取 `filesDir/books/<LibraryId>/<fileGeneration>.book`。磁盘名使用不透明 UUID，不使用书名、源路径或数字书籍 ID。文件工厂不能访问源库或创建缺失副本；拒绝符号链接、目录、空文件和已知长度不符。文件丢失返回缺失，权限和其它 I/O 错误分别映射为结构化失败，不携带异常全文。

长度校验不能替代加载阶段的格式完整性验证；大小未知时仍须在发布完整记录前完成验证。文件代次不可原地改写或重用；更新与清理必须协调已打开句柄，避免读者得到半文件。读取与句柄交付规则见 [存储约束](../storage/AGENTS.md)。

## FileProvider

FileProvider authority 为 `${applicationId}.books`，非导出，只开放 `filesDir/books/`。数据库使用独立数据库目录，封面、暂存和凭据不得存入 `books`。provider 的目录限制不查询下载清单，因此只有完整发布文件允许进入该目录；生成 URI 前仍必须通过副本读取契约。第三阶段负责友好显示名、MIME、外部打开和临时只读 URI 授权，不得授予写权限。当前没有 URI 授予或阅读器调用。

生产副本处理器先在 `filesDir/book-staging/<TaskId>/` 完整传输并校验，生成路径各组件拒绝符号链接，暂存清理不跟随目录内的链接，再重命名为不可变 books 代次并同步相关目录；清单和任务成功在同一 SQLite 事务发布。数据库未引用的崩溃遗留代次在后续传输时回收；文件工厂保留活跃句柄的旧代次直到关闭。普通读取器和清单更新必须共享状态库的 `copyAccess`，不能另建独立读取门。

## 验证

`app/src/androidTest` 的 `BookFileProviderTest` 覆盖完整副本 URI／字节读取、私有目录拒绝、路径穿越、符号链接逃逸及 debug authority 隔离。这些测试不能替代真实阅读器验收；执行与卸载规则见模块约束，实际结果记录于 [第一阶段验证记录](../../../../../../../../verification/phase-1.md)。
