# Android 模块开发约束

本文件适用于 `app/`，与根 [AGENTS.md](../AGENTS.md) 一起遵循；产品行为以 [spec.md](../spec.md) 为准。

## 构建与实现

- 日常构建和检查使用根目录 Gradle Wrapper。构建 JDK 为 21；Java 兼容级别及 Kotlin JVM target 显式统一为 17。最低 API 30、compile/target API 36、Build Tools 36.0.0。
- 使用 AGP 内置 Kotlin，不叠加 `org.jetbrains.kotlin.android`；Compose 编译器插件与实际 Kotlin 编译器版本一致。具体版本由构建配置和锁文件维护，不在说明文档重复维护版本表。
- 单 `:app` 模块按职责分包，在需要注入组件时手工构造应用依赖容器，不引入 DI 框架、KSP、Graph SDK 或额外业务模块。业务持久化使用 Android 原生 SQLite；WorkManager 的传递 Room 依赖不作为业务数据库。
- 依赖版本与更新规则见 [gradle/AGENTS.md](../gradle/AGENTS.md)。模块启用严格 dependency locking，普通验证不带 `--write-locks`。

## 基础契约导航

- [身份模型约束](src/main/java/io/github/chenxiex/calibrecloud/model/AGENTS.md)：书库、书籍、格式、后端定位和逻辑路径校验。
- [存储约束](src/main/java/io/github/chenxiex/calibrecloud/storage/AGENTS.md)：完整记录查询、普通读取、句柄生命周期和显式操作边界。
- [任务约束](src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)：固定请求、来源优先级、依赖、已读写回的变更列表与紧接同步。
- [文件提供约束](src/main/java/io/github/chenxiex/calibrecloud/files/AGENTS.md)：私有文件代次、书籍 provider 的 URI 与临时只读授权。
- [图书馆查询约束](src/main/java/io/github/chenxiex/calibrecloud/library/AGENTS.md)：本地索引、搜索、筛选、分类、排序与默认格式。
- [界面约束](src/main/java/io/github/chenxiex/calibrecloud/ui/AGENTS.md)：分页容器、无动画、测试标签与图书馆页面状态。
- [应用状态约束](src/main/java/io/github/chenxiex/calibrecloud/state/AGENTS.md)：原生 SQLite、候选配置、身份绑定、最小清单及事务。

## Manifest 与入口

- debug 使用 `.debug` application ID 后缀和可辨认名称，AndroidTest 使用独立测试包。provider authority 均由 `${applicationId}` 派生；书籍 provider 使用 `${applicationId}.books`，只提供下载清单中已发布的完整副本。
- OAuth 配置与回调地址验证见 [授权模块](src/main/java/io/github/chenxiex/calibrecloud/auth/AGENTS.md)。缺少完整配置时回调接收器显式禁用，使用各变体独立的 `.disabled` scheme；不能阻止本地构建、发起登录或显示虚构成功。
- 当前入口已接入本地目录授权，开发约束见 [本地授权](src/main/java/io/github/chenxiex/calibrecloud/storage/local/AGENTS.md)。本地授权恢复只检查平台授权与私有配置，显式“验证／同步元数据”经持久候选任务调用只读 SAF 后端，快照交给同一元数据导入器原子发布；OneDrive 通过持久候选任务调用个人文件后端，完整目录浏览和只读快照均不在 UI 中直接访问 Graph，目录翻页只操作本地完整结果；授权与有效令牌刷新由应用容器共享组件管理。注册一次性 `QueueWorker`，提交与控制后经 WorkManager 唤醒同一持久队列和执行锁，不创建周期任务。保留默认 initializer 的移除配置，由 Application 的 `Configuration.Provider` 支持按需初始化；配置遵循[官方初始化说明](https://developer.android.com/develop/background-work/background-tasks/persistent/configuration/custom-configuration)。长任务使用 `dataSync` 前台服务与静态通知，权限及类型遵循[官方长任务说明](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running)，平台启动限制和任务配额不能靠注册 worker 消除。
- 只申请已实现路径需要的权限；检查依赖合并的 Manifest，不因声明依赖而开放网络、广泛存储或后台权限。网络入口使用 HTTPS。
- 凭据和授权数据不能进入备份／设备迁移；禁用备份并排除普通应用数据域；授权密文置于 noBackupFilesDir，使用不可导出的 Keystore AES-GCM 密钥，不能移至普通 files/cache。
- 应用控制的页面遵循 R20：不通过滚动遍历内容、不添加过渡或加载动画，状态在灰度下可辨认。静态占位入口使用文字和布局，避免默认点击效果；内容超出一页时采用显式分页。新页面使用共享主题与组件，不各自实现同用途控件或直接写样式值，见[界面约束](src/main/java/io/github/chenxiex/calibrecloud/ui/AGENTS.md)。

## 验证与证据

- 测试随实际功能路径加入，不添加常量或空 Activity 的占位测试；`NO-SOURCE` 或生成 AndroidTest APK 不能记为功能测试通过。
- 工程与构建配置变更按受影响路径执行 debug/release 构建和 lint；生成依赖锁后再运行不更新锁的构建，核对锁文件未变化。
- 检查实际 APK 的最低系统、包标识、可调试状态及导出组件；OAuth 配置缺失时核对回调禁用。release 签名只从被忽略的 `local.properties` 的 `release.*` 四项读取，未配置时生成未签名产物；密钥库和密码不得写入仓库或日志，agent 不生成、移动或替换用户的发布密钥库；除非用户明确要求，不在真机安装或覆盖正式包，验证与测试始终只用 debug 包。
- 设备测试的 runner 是 androidTest 中的 `io.github.chenxiex.calibrecloud.AwakeTestRunner`，手动 `am instrument` 也要指定它。它在整轮测试期间持有 partial wake lock：PA6 亮屏时若无 wake lock 也会挂起 CPU，每分钟只醒约 7 秒，测试进程会随之冻结。
- ADB 安装前先从 APK 核对 debug application ID，只使用测试书库副本；记录结果后卸载 debug 与测试包，不覆盖正式应用或访问正式数据。
- 实际命令、结果和未完成的验收分别写入 [第一阶段记录](verification/phase-1.md)、[第二阶段记录](verification/phase-2.md)、[第三阶段记录](verification/phase-3.md)、[第四阶段记录](verification/phase-4.md) 与 [第五阶段记录](verification/phase-5.md)。构建及模拟平台／服务响应不能替代对应阶段的真实设备／上游验证；在目标真机自动执行的测试或 ADB 操作按其实际覆盖范围计为真机证据。

## 测试设计与共同验收

实施前将相关需求映射到检查、预期结果和证据来源，再列出确实需要人工介入的操作。共同验收由用户审阅 agent 的客观结果并确认人工体验组成，不要求用户重复执行已自动覆盖的全部路径。

- 接口回归覆盖网络／授权失败、读取中断、限流、版本变化、缺失、暂停／取消及恢复等故障组合。使用可插拔后端、传输流和时间等已有注入边界，保障状态、调用次数／范围、持久证据与最终内容；不得为测试便利改变生产行为。需要真实 SQLite、文件、URI 或 Android 生命周期行为的部分放在相关平台测试或 ADB 探测中。
- 真实 SAF、Graph 或其它上游的最小集成检查使用生产授权与后端，由 agent 自动执行。用户准备专用测试副本并自行登录／授权；agent 核对实际选择范围及已知样本，再执行只读源访问、响应能力与内容校验。真实集成测试须显式启用，普通自动回归不能默认访问用户账号或依赖人工登录。源只读允许独立 debug 应用维护自己的任务、暂存和缓存，不允许借此修改源文件；凭据留在应用授权存储，不导出或写入日志。
- 需要验证真实后端与错误恢复衔接时，可在测试侧包装真实读取流，按确定偏移注入一次中断，再恢复生产读取并核对持久断点、实际接续偏移与最终字节。使用同一应用依赖和共享执行锁；完成其它任务后运行专用探测，避免并发执行。测试注入故障、持久库重开、真实进程终止与物理断网须分别标明，不能互相冒充证据。
- 物理断网、用户计时暂停／强停和云端覆盖／改名不作为每轮人工验收的固定组合；接口回归和真实只读能力检查可覆盖的部分自动执行。平台约束要求的退后台、实际进程终止、重启、通知／权限仍按对应阶段真实验证，优先 ADB 自动操作。确需断开无线 ADB 的操作才由用户介入恢复连接；发现具体设备／上游行为差异时增加定向实测。
- 人工指南只保留用户必须执行的准备、约定的较高风险源操作，以及动画、灰度、残影、触控／阅读体验等自动证据不能确认的内容，给出最短步骤与反馈格式；agent 准备页面、执行其余客观检查并记录结果。不要求用户提供密码／令牌，也不因源读取授权推导出源写入授权。第四阶段条件提交、冲突与 Calibre 一致性仍须真实写入测试副本验证，只读探针不能替代。
- 记录每项检查的实际层次、执行方式、结果和限制，区分沿用证据与本轮新执行；模拟失败不计为真实服务失败实测，源版本不变或最终下载成功不足以证明断点续传。复用证据以未受改动影响为前提；出现新变化、失败或具体未解风险时补测相应路径。共同验收会话结束后卸载独立 debug／测试包并删除本轮专用设备测试数据，源测试目录的保留／删除依照用户安排。
