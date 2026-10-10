# OneDrive 后端

遵循上级存储约束和规格 R03–R06、R08–R11、R15、R31–R35。唯一的源写入是已读写回的条件上传（见下文）。生产运行时所有方法只由持久源任务处理器调用；UI、普通副本读取器不得直接发 Graph 文件请求。显式启用的只读集成测试可经生产后端检测真实服务能力，遵循下文验收边界。

## 请求开销（R08，Q52–Q55）

列目录是高开销请求，只有目录选择调用（`listAllDirectories`，一次一个目录）；其它操作不得列出子项目、不得递归遍历。每个操作的 Graph 请求数是契约的一部分，改动须同步更新 JVM 请求序列测试：

| 操作 | Graph 请求 |
| --- | --- |
| `lookup`／`version` | 1 个按路径取项目 |
| `open`／`openRange` | 0 个（使用 lookup 返回的下载地址）；没有下载地址时 1 个 `/content` |
| `openCover` | 1 个按路径取项目并 `$expand=thumbnails` |
| `acquireSnapshot` | 数据库 cTag 未变化时 1 个；变化时 3 个（数据库加两个日志名）及一遍内容读取 |
| `replaceDatabase`（已读写回推送） | cTag 已不等于基底时 1 个；否则 2 个（按路径取项目加一次 `PUT` 上传） |
| 账号核对 | 0 个；只有会话没有 subject 时回退 `discover` |

同一任务内不重复请求同一项目，也不为消除两次同步之间的过期而复查；过期由下一次同步发现，按路径找不到文件时由任务层按 R11 同步一次后重试一次。

- `discover` 只在登录后的目录选择中调用，使用已授权 Microsoft consumers ID token 的 subject（带固定来源前缀）、本人 `/me/drive` 的 drive ID 及根 item ID 建立稳定定位；可信注入未提供 subject 时才使用可用的 `owner.user.id`，不能假定真实个人 drive 总会返回该字段，只接受 `driveType=personal`。账号标识不依赖 Graph owner 的显示形式，重新登录换账号仍按完整位置隔离。不依赖显示名／邮件地址，不进入 `remoteItem` 或其它 drive。Graph `shared` facet 仅表示项目曾分享给别人，不代表外部共享来源；本人 drive 内满足父级边界的普通项目不能因该字段被拒绝。
- 目录浏览使用 `listAllDirectories`，同一次任务只校验一次本人 drive 和 parent 到选定根的祖先链，不指定 `$top=3`，沿服务端 nextLink 完整取得子目录。每个服务端页前后检查任务控制；nextLink 只能指向同一 HTTPS Graph children 端点，循环链接和重复 item ID 拒绝。调用方保存完整结果并本地翻页，不为界面翻页访问源。目录选择仅显示普通本人目录，忽略文件及 remoteItem／deleted／package 项；需要显示的目录仍检查 drive 和立即父 ID。源路径解析和快照检查保持原有严格范围，不因无关项目过滤而放宽。
- 之后的源访问只在本地比较当前登录 subject（带同一来源前缀）与 `LibraryLocation.accountId`，不再请求 `/me/drive` 和 drive 根；换账号由此拦截。drive 或根已不可访问时由后续请求的 Graph 403／404 按现有映射处理。
- 源文件以选定根 item ID 加 `RelativeSourcePath` 用 [路径寻址](https://learn.microsoft.com/en-us/graph/onedrive-addressing-driveitems) 直接定位（`items/{根ID}:/{路径}`），每段按 RFC 3986 编码，未保留字符以外一律百分号编码；模型已拒绝 `..`、`:` 与空段，因而不会越出根。响应须为本 drive 的文件，拒绝 remoteItem／deleted／package 与其它 drive；路径上是目录或 404 均为源缺失。请求不带 `$select`，使默认响应包含 `@microsoft.graph.downloadUrl`；版本只使用文件 cTag，不能用 eTag 或书籍时间替代。
- `OneDriveSourceFile` 携带的预签名下载地址只在当次任务内存中使用，`toString` 不包含它，不进入恢复记录、日志或 reader URI；必须是无用户信息的 HTTPS，否则视为没有下载地址并改用 `/content` 重定向。
- Graph 401 最多刷新授权一次；403 表示授权失效。429／5xx 归为 `THROTTLED` 并将 Retry-After 交回任务调度器，缺失时使用 30 秒有限重试间隔；后端不阻塞睡眠。调度器决定重试次数上限，不以网络／授权失败认定源删除。
- 下载重定向只接受 HTTPS，由单独无授权 client 处理，最多五跳。生产保持默认独立 contentClient；注入 contentClient 和 GraphJsonDecoder 仅用于可信网络测试。不得保存／打印响应体、token、预签名下载 URL，不能把它们作为 reader URI。
- 封面按导入的精确图片相对路径一次取得该图片 item 及其 [thumbnails 集合](https://learn.microsoft.com/en-us/graph/api/driveitem-list-thumbnails?view=graph-rest-1.0)（`$expand=thumbnails`），同时返回图片 cTag 供任务记录版本，不得请求 EPUB／PDF 等格式的缩略图。优先选最小满足目标宽高的尺寸，否则选最大可用尺寸；没有安全可用缩略图或缩略图已失效时用同一响应的下载地址回退封面图片原内容，不再请求 Graph。缩略图 URL 仅用于当次无授权读取，不进入缓存标识或日志；授权／网络／限流失败仍交给任务调度器，不隐式吞掉。调用方负责流关闭、图像解码与应用私有缓存，验收回调只提供成功打开的缩略图宽高。
- 单格式副本续传由任务重新 `lookup` 一次取得 cTag、长度和新下载地址，cTag 与断点证据一致才调用 `openRange`；只向实际内容 URL 发送 `Range` 与 `Accept-Encoding: identity`，不向 Graph `/content` 发送 Range、不携带 Graph 授权。仅接受匹配断点及总长的 206；200／416 关闭响应并返回无法范围读取，任务层完整重传。网络流中断交给队列重试，响应长度／范围异常归为损坏或冲突，不能拼接。下载 URL 不进入恢复记录。
- 快照先按路径取 metadata.db；cTag 等于调用方给出的上次导入版本时返回 null，不读取内容。否则按文件名查询 `metadata.db-wal` 与 `metadata.db-journal`（404 为不存在），存在且非空或为目录即版本冲突；`-shm` 只伴随 WAL、超级日志 `-mj*` 只在有 `-journal` 时有效，不再列根目录匹配前缀。随后用下载地址完整读取一遍并 fsync，校验长度与注入的私有 SQLite validator，以读取前的 cTag 发布不可变 UUID 文件；不做读后复查和第二遍读取（Q55），失败仅删除本次 part。这些观察不保证任意源并发安全，写回另以条件提交保护。没有一般上传与删除。

## 已读写回提交

- `replaceDatabase` 实现 R15 的 OneDrive 推送：先按 `staged` 重算 SHA-256 并与暂存散列核对（不符为可重试的 `LOCAL_IO`，不发请求），超过简单上传上限 250 MB 时拒绝（`UNSUPPORTED_OPERATION`，不重试，不使用上传会话）；按路径取一次 `metadata.db` 得到 item ID 与当前 cTag，已不等于基底即冲突、不上传；否则 `PUT /drives/{d}/items/{id}/content` 携带 `If-Match: <基底 cTag>` 上传整个文件。不能省去取项目改为按路径上传：探测表明按路径 `PUT` 即使带 `If-Match`，目标不存在时也会新建文件（201），`metadata.db` 缺失时会凭空造出一个；按 item ID 上传则不会创建。
- 412、409 及取项目后 item 被替换的 404 为 `PushOutcome.Conflict`；2xx 响应须是同一 item、文件、大小等于上传长度并带 cTag，新 cTag 即推送后的版本。请求发出后的 IO 异常、429、5xx 与无法核对的 2xx 响应均为结果不明，作为可重试失败交回处理器重跑一整轮；401 按通用规则刷新令牌一次后重发同一请求，403 为授权失效。上传的响应等待上限 120 秒，超时同样是结果不明。
- 一次上传要么替换要么不替换，因此不使用 `PushJournal`，`finishPendingPush` 为空操作。`writeCapability` 不联网，只在本地比较当前登录 subject 与书库账号，不一致或未登录为 `AUTHORIZATION_REQUIRED`；没有保存 subject 的旧登录须重新登录后才可写。
- 日志只记录 `upload_base_changed`、`upload_status_<code>`、`upload_response` 等固定标签，不记录令牌、URL、item ID 或数据库内容。
- JVM `OneDriveSourceBackendTest` 断言推送的请求序列、`If-Match`、上传字节及各故障分类，`OneDriveLibrarySource` 层断言冲突后重新获取快照再上传的整轮请求序列。真实写入由 `OneDriveReadStatusCommitDeviceTest` 验证：只能在用户授权的专用 OneDrive 测试书库上以 `-e oneDriveWrite true` 显式启用；冲突用例由测试侧上传另一写入者的数据库，另需 `-e oneDriveConflict true`。结果见[第四阶段验证记录](../../../../../../../../../verification/phase-4.md)。

JVM 测试使用真实 OkHttp Request／Response 的注入 fixture，逐项断言请求序列（路径编码、无 children、同一项目只取一次），覆盖本地身份比较、目录分页、父级边界、下载地址与凭据重定向、HTTP 失败及快照日志与未变化跳过。Android JSON 解码和真实 Graph／设备验收仍须使用平台测试与专用测试书库，证据放在 `app/verification/phase-2.md`。

真实只读验收使用显式启用的设备测试，经生产 OneDrive 后端访问用户准备的专用测试目录；用户负责登录、授权及准备源文件，agent 自动执行读取并检查结果。本次变更涉及的完整内容、非零偏移 Range 或缩略图等端点必须取得真实成功响应并核对内容，不能用 fixture 响应冒充。已有入口见 [OneDriveReadOnlyAcceptanceTest](../../../../../../../../androidTest/java/io/github/chenxiex/calibrecloud/tasks/copies/OneDriveReadOnlyAcceptanceTest.kt)；专用目录和样本由各阶段指南配置，不固定为通用测试路径。

续传集成可在测试侧包装真实生产后端返回的流，在指定字节处注入可控中断，再验证非零断点恢复及最终完整字节；包装不修改生产行为，不伪造后端的 Range 响应。无需为这些确定性故障反复人工关闭 Wi-Fi、等待超时或改动云端源；真实上游请求仍只读，私有队列执行仍使用应用共享执行锁。授权或源配置不足时记录未完成项，仅有故障注入或模拟响应的结果不能记为真实上游通过；包装真实流的集成结果须分别记录真实响应校验与注入故障的范围。

Graph 边界依据：[本人 drive](https://learn.microsoft.com/en-us/graph/api/drive-get?view=graph-rest-1.0)、[账号唯一 ID](https://learn.microsoft.com/en-us/graph/api/resources/identity?view=graph-rest-1.0)、[目录分页](https://learn.microsoft.com/en-us/graph/api/driveitem-list-children?view=graph-rest-1.0)、[shared facet](https://learn.microsoft.com/en-us/graph/api/resources/shared?view=graph-rest-1.0)、[driveItem cTag](https://learn.microsoft.com/en-us/graph/api/resources/driveitem?view=graph-rest-1.0)、[内容重定向及无 Authorization 的下载 URL](https://learn.microsoft.com/en-us/graph/api/driveitem-get-content?view=graph-rest-1.0) 和 [Retry-After](https://learn.microsoft.com/en-us/graph/throttling)。

- 真机请求计数由测试侧在两个 client 上加计数拦截器，只记录方法、端点类别（items-path／children／thumbnails／content／me／items-id／download）与状态码，不记录 URL、路径或令牌，不改变生产行为。
- 安全诊断只记录固定阶段标签／HTTP 状态码，不包含响应正文、账号标识、目录名、ID token、access token、URL 或异常全文。

真实个人 OneDrive 返回的 thumbnail 描述尺寸与实际解码尺寸可能不同；本轮描述为 800×800、实际图片为 600×800。描述只用于选择候选，解码／缩放必须依据图片内容，验收分别记录两者，不要求图片填满描述尺寸。实际来源和证据见第二阶段记录。
