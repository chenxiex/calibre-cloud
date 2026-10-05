# 存储基础契约

本目录实现步骤 02 的身份与应用副本读取边界，对应 R03–R07、R28、R31–R33。当前入口尚未接入这些契约；这里没有真实本地／OneDrive 后端、持久下载清单、源传输或任务执行器。

## 身份与定位

`model/StorageIdentity.kt` 将源位置、内部书库身份、书籍与格式分开。源位置分别保存本地 provider authority／tree document ID，或 OneDrive 稳定账号标识／drive ID／根 item ID。`LibraryIdentity` 绑定不透明 `LibraryId`、位置和书库代次；第二阶段验证后端负责相同位置复用和不兼容替换识别，替换应分配新内部身份，不能仅按数字书籍 ID 恢复旧数据。

`BookKey` 包含书库、源数字 ID 和源 UUID。`CopyKey` 再加入规范化格式；源文件定位由 `FormatResource` 的后端类型携带，不把临时下载 URL 当作定位。`FileVersion` 是后端产生的文件版本，不是书籍修改时间。其字符串表示隐藏 token；位置、标题等内容也不得写入日志。

逻辑相对路径拒绝绝对路径、空路径段、点路径段、反斜线、冒号和控制字符；格式只接受字母数字并用 `Locale.ROOT` 规范化。逻辑校验不替代后端的授权根校验：本地 document ID 和 OneDrive item ID 都是不透明标识，后端必须验证它们属于选定根，不能将这些标识拼接成未经校验的磁盘路径。自定义栏目用导入 ID 和 `#lookup_name` 标识，由后续动态发现提供；没有固定已读栏目。

## 完整副本读取

`PrivateCopyReader` 只依赖 `CompleteCopyQuery`、应用句柄工厂和注入的 I/O dispatcher。查询只能返回已经验证并原子发布的完整记录；暂存和执行中任务不进入该查询。读取返回 `Available(handle, savedVersion)`、`Missing` 或结构化失败，没有源回退、隐式下载或等待任务选项。源尚未确认或已确认缺失不会阻止完整旧副本读取；网络／授权失败不能更改清单的源状态。

`PrivateBookFiles` 在 `filesDir/books/<LibraryId>/<fileGeneration>.book` 打开现有副本，不创建目录或文件。磁盘名只使用 UUID，不使用书名、源路径或数字书籍 ID。拒绝符号链接、目录、空文件和已知长度不符；文件丢失返回缺失，访问拒绝和其它 I/O 失败保留可辨认错误，不携带异常全文。长度检查是本次局部校验，不能代替后续加载阶段的格式完整性验证；大小未知时仍需在发布完整记录前完成验证。

调用方拥有 `ApplicationCopyHandle`，必须关闭并在后台执行后续流读取；取消发生在结果交付前时，读取器会关闭未交出的句柄。后续维护实现必须协调使用中句柄与不可变文件代次：先验证暂存内容，再发布新记录；不得原地改写已发布文件或重用文件代次。当前没有实现更新／清理并发协议，不以路径检查承诺任意并发下安全。

## 显式操作和文件提供

`SourceSynchronization` 仅声明任务处理器调用的显式元数据、格式和封面加载入口；`CopyMaintenance` 仅声明精确副本移除和元数据清理。没有一般源上传／删除接口。`ReadStatusIntent` 只表达书籍、动态栏目和固定布尔目标，第四阶段再实现安全准备／提交和写后刷新协议。

FileProvider authority 为 `${applicationId}.books`，非导出，只开放 `filesDir/books/`。数据库使用独立数据库目录，封面、暂存和凭据不得存入 `books`。provider 的目录限制不查询下载清单，因此只有完整发布文件允许进入该目录；生成 URI 前仍必须通过副本读取契约。第三阶段负责友好显示名、MIME、外部打开和临时只读 URI 授权，不得授予写权限。当前没有 URI 授予或阅读器调用。

必要测试分别位于 `src/test` 的身份／副本读取测试和 `src/androidTest` 的 provider 路径测试；实际结果与未完成验收见 [第一阶段验证记录](../../../../../../../../verification/phase-1.md)。
