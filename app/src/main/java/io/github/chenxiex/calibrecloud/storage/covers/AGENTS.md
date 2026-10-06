# 封面缓存

遵循上级存储约束与 R06、R10、R12、R17–R18。`CoverRepository.read` 只读取已发布的私有 PNG，在 I/O dispatcher 解码，不访问源、不提交任务。键包含 LibraryId、源数字 ID 与 UUID，文件代次为不透明 UUID，独立于 `books/` 与 provider；无完整记录、文件缺失或损坏时返回标题占位所需的空结果。

`cover_cache` 的指针与封面任务成功在同一状态库事务发布，同时核对当前书库、书籍 UUID、导入代次及任务控制。发布前处理器负责解码、缩放与 fsync，失败保留旧图。缓存读取、发布和限额回收共享 repository 的锁；bitmap 交付后与磁盘文件生命周期独立。默认封面配额 32 MiB，按最近读取／发布时间淘汰，下一次发布回收未引用的完整代次。该回收只遍历本 repository 的 UUID 图片目录，不参与书籍副本、快照或保护资料维护；跨功能精确清理在步骤 08 接入同一发布协议。

处理器使用 `cover-staging/<TaskId>/`，编码源最多 12 MiB，先解析图片尺寸，拒绝边长超过 32768 或总像素超过一亿，再按二的幂采样并缩放为最多 256×384；不在 UI 线程解码。依据 [Android 大图片采样说明](https://developer.android.com/topic/performance/graphics/load-bitmap)。暂停／取消、失败均丢弃本次暂存，恢复重新取得版本及图片，不复用半解码图；封面不需要下载书籍格式。完成封面缓存事件只在事务成功后发布。

OneDrive 优先确切 `cover.jpg` 图片项目的 thumbnails，无可用缩略图回退同一图片；后端边界和上游证据见相邻 OneDrive 约束。平台测试用独立 SQLite／图片 fixture 验证发布与失败；真实只读验收须显式启用并使用专用测试书库，不以模拟缩略图响应关闭上游验收。
