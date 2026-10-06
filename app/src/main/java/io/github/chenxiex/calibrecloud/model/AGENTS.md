# 存储身份模型开发约束

本文件适用于 `model/`；同时遵循 [Android 模块约束](../../../../../../../../AGENTS.md)。需求以 [spec.md](../../../../../../../../../spec.md) 的 R03、R05 为准。

## 身份与定位

[StorageIdentity.kt](StorageIdentity.kt) 将源位置、内部书库身份、书籍与格式分开。源位置分别保存本地 provider authority／tree document ID，或 OneDrive 稳定账号标识／drive ID／根 item ID。`LibraryIdentity` 绑定不透明 `LibraryId`、位置和书库代次；第二阶段验证后端负责相同位置复用和不兼容替换识别，替换应分配新内部身份，不能仅按数字书籍 ID 恢复旧数据。

`BookKey` 包含书库、源数字 ID 和源 UUID。`CopyKey` 再加入规范化格式；源文件定位由 `FormatResource` 的后端类型携带，不把临时下载 URL 当作定位。`FileVersion` 是后端产生的文件版本，不是书籍修改时间。其字符串表示隐藏 token；位置、标题等内容也不得写入日志。

逻辑相对路径拒绝绝对路径、空路径段、点路径段、反斜线、冒号和控制字符；格式只接受字母数字并用 `Locale.ROOT` 规范化。逻辑校验不替代后端的授权根校验：`SourceFileLocator.Relative` 是提交时冻结的逻辑路径，不伪装为已解析的 document/item ID；本地 document ID 和 OneDrive item ID 都是不透明标识，后端必须验证它们属于选定根，不能将这些标识拼接成未经校验的磁盘路径。自定义栏目用导入 ID 和 `#lookup_name` 标识，由后续动态发现提供；没有固定已读栏目。
