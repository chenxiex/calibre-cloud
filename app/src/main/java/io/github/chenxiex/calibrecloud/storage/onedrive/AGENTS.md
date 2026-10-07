# OneDrive 只读后端

遵循上级存储约束和规格 R03–R06、R08–R09、R31–R35。生产运行时所有方法只由持久源任务处理器调用；UI、普通副本读取器不得直接发 Graph 文件请求。显式启用的只读集成测试可经生产后端检测真实服务能力，遵循下文验收边界。

- `discover` 使用已授权 Microsoft consumers ID token 的 subject（带固定来源前缀）、本人 `/me/drive` 的 drive ID 及根 item ID 建立稳定定位；可信注入未提供 subject 时才使用可用的 `owner.user.id`，不能假定真实个人 drive 总会返回该字段，只接受 `driveType=personal`。账号标识不依赖 Graph owner 的显示形式，重新登录换账号仍按完整位置隔离。不依赖显示名／邮件地址，不进入 `remoteItem` 或其它 drive。Graph `shared` facet 仅表示项目曾分享给别人，不代表外部共享来源；本人 drive 内满足父级边界的普通项目不能因该字段被拒绝。
- 目录浏览使用 `listAllDirectories`，同一次任务只校验一次本人 drive 和 parent 到选定根的祖先链，不指定 `$top=3`，沿服务端 nextLink 完整取得子目录。每个服务端页前后检查任务控制；nextLink 只能指向同一 HTTPS Graph children 端点，循环链接和重复 item ID 拒绝。调用方保存完整结果并本地翻页，不为界面翻页访问源。目录选择仅显示普通本人目录，忽略文件及 remoteItem／deleted／package 项；需要显示的目录仍检查 drive 和立即父 ID。源路径解析和快照检查保持原有严格范围，不因无关项目过滤而放宽。
- 源文件按 `RelativeSourcePath` 逐层解析，检查每层 parentReference 的 drive 和立即父 ID；稳定定位为 drive/item ID，版本只使用文件 cTag，不能用 eTag 或书籍时间替代。
- Graph 401 最多刷新授权一次；403 表示授权失效。429／5xx 归为 `THROTTLED` 并将 Retry-After 交回任务调度器，缺失时使用 30 秒有限重试间隔；后端不阻塞睡眠。调度器决定重试次数上限，不以网络／授权失败认定源删除。
- 下载重定向只接受 HTTPS，由单独无授权 client 处理，最多五跳。生产保持默认独立 contentClient；注入 contentClient 和 GraphJsonDecoder 仅用于可信网络测试。不得保存／打印响应体、token、预签名下载 URL，不能把它们作为 reader URI。
- 封面按导入的精确图片相对路径解析，只读取该图片 item 的 [thumbnails 集合](https://learn.microsoft.com/en-us/graph/api/driveitem-list-thumbnails?view=graph-rest-1.0)，不得请求 EPUB／PDF 等格式的缩略图。优先选最小满足目标宽高的尺寸，否则选最大可用尺寸；没有安全可用缩略图或缩略图已失效时回退同一封面图片的原内容。缩略图 URL 仅用于当次无授权读取，不进入缓存标识或日志；授权／网络／限流失败仍交给任务调度器，不隐式吞掉。调用方负责流关闭、图像解码与应用私有缓存，验收回调只提供成功打开的缩略图宽高。
- 单格式副本续传重新解析源身份、cTag 和长度，只向新取得的实际内容 URL 发送 `Range` 与 `Accept-Encoding: identity`，不向 Graph `/content` 发送 Range、不携带 Graph 授权。仅接受匹配断点及总长的 206；200／416 关闭响应并返回无法范围读取，任务层完整重传。网络流中断交给队列重试，响应长度／范围异常归为损坏或冲突，不能拼接。下载 URL 不进入恢复记录。
- 快照先检查事务日志，完整复制后 fsync，再检查日志和 cTag，第二遍完整源读取比较 SHA-256，最后复查日志与版本并执行注入的私有 SQLite validator。成功发布不可变 UUID 文件，失败仅删除本次 part；这些观察不保证任意源并发安全。未实现一般上传、删除和条件写回。

JVM 测试使用真实 OkHttp Request／Response 的注入 fixture，覆盖身份、目录分页、父级边界、凭据重定向、HTTP 失败及快照冲突。Android JSON 解码和真实 Graph／设备验收仍须使用平台测试与专用测试书库，证据放在 `app/verification/phase-2.md`。

真实只读验收使用显式启用的设备测试，经生产 OneDrive 后端访问用户准备的专用测试目录；用户负责登录、授权及准备源文件，agent 自动执行读取并检查结果。本次变更涉及的完整内容、非零偏移 Range 或缩略图等端点必须取得真实成功响应并核对内容，不能用 fixture 响应冒充。已有入口见 [OneDriveReadOnlyAcceptanceTest](../../../../../../../../androidTest/java/io/github/chenxiex/calibrecloud/tasks/copies/OneDriveReadOnlyAcceptanceTest.kt)；专用目录和样本由各阶段指南配置，不固定为通用测试路径。

续传集成可在测试侧包装真实生产后端返回的流，在指定字节处注入可控中断，再验证非零断点恢复及最终完整字节；包装不修改生产行为，不伪造后端的 Range 响应。无需为这些确定性故障反复人工关闭 Wi-Fi、等待超时或改动云端源；真实上游请求仍只读，私有队列执行仍使用应用共享执行锁。授权或源配置不足时记录未完成项，仅有故障注入或模拟响应的结果不能记为真实上游通过；包装真实流的集成结果须分别记录真实响应校验与注入故障的范围。

Graph 边界依据：[本人 drive](https://learn.microsoft.com/en-us/graph/api/drive-get?view=graph-rest-1.0)、[账号唯一 ID](https://learn.microsoft.com/en-us/graph/api/resources/identity?view=graph-rest-1.0)、[目录分页](https://learn.microsoft.com/en-us/graph/api/driveitem-list-children?view=graph-rest-1.0)、[shared facet](https://learn.microsoft.com/en-us/graph/api/resources/shared?view=graph-rest-1.0)、[driveItem cTag](https://learn.microsoft.com/en-us/graph/api/resources/driveitem?view=graph-rest-1.0)、[内容重定向及无 Authorization 的下载 URL](https://learn.microsoft.com/en-us/graph/api/driveitem-get-content?view=graph-rest-1.0) 和 [Retry-After](https://learn.microsoft.com/en-us/graph/throttling)。

- 安全诊断只记录固定阶段标签／HTTP 状态码，不包含响应正文、账号标识、目录名、ID token、access token、URL 或异常全文。

真实个人 OneDrive 返回的 thumbnail 描述尺寸与实际解码尺寸可能不同；本轮描述为 800×800、实际图片为 600×800。描述只用于选择候选，解码／缩放必须依据图片内容，验收分别记录两者，不要求图片填满描述尺寸。实际来源和证据见第二阶段记录。
