# OneDrive 授权开发约束

本目录遵循根与 [Android 模块](../../../../../../../../AGENTS.md) 约束，产品行为以 R08、R31、R33–R35 为准。面向开发者的注册与配置指引见 [app/README.md](../../../../../../../../README.md#onedrive-配置)。

## 构建配置

- 只读取根 `local.properties` 中约定的三项 OAuth 属性；三个值全部非空才启用，两变体接收范围须隔离。不读取或打包 client secret，不更改 `sdk.dir` 等已有属性。
- 缺配置或部分配置时，两变体仍须可构建并使用本地授权；禁用回调接收器，使用各变体独立的禁用 scheme，生成的 client ID 与回调值均为空，不打包部分配置。
- 完整配置非法或范围重叠时，构建失败并指出相应属性，不输出属性值。使用结构化 URI 解析与安全的生成字符串，不将属性直接拼接为代码。
- 回调须为小写自定义 scheme、非空绝对字面路径，可带小写字面 host；拒绝 HTTP(S)、文件／内容等保留 scheme、凭据、端口、query、fragment、通配 host、转义路径和 XML 特殊字符。
- 构建校验、Manifest placeholders 和运行配置共用解析结果；运行配置由 BuildConfig 生成，不维护第二套 scheme 常量。

## 回调接收与地址验证

- `app/oauth-manifests/` 提供带 host 和无 host 的字面模板，由变体 sourceSet 选择；使用应用 OneDriveCallbackActivity，并移除依赖的 AppAuth receiver，避免遗留宽泛 filter。无 host 时省略 host/path 属性，不生成空属性。
- 带 host 时按 scheme、host 和精确 path 匹配；无 host 的 `scheme:/oauth2redirect` 须正确支持，但 Android 忽略其路径 filter，因此同 scheme 的任意另一变体接收范围都会重叠，必须拒绝。同 scheme 仅在双方都有 host 且 host 或精确 path 不同的情况下允许。规则依据见 [Android data 元素](https://developer.android.com/guide/topics/manifest/data-element)。
- 完整回调校验由 `OneDriveOAuthConfiguration.acceptsCallback` 承担，包含无 host 的路径检查；OAuth 返回的 query 留待协议流程处理。后续协调器处理结果前还必须核对待处理事务、state 与重复交付，不能把地址匹配视为已授权。

## 授权阶段与隐私

- 全球个人账号 `consumers` 端点、授权码、S256 PKCE 与 `openid`、`offline_access`、`https://graph.microsoft.com/Files.ReadWrite` 范围由 AppAuth 构建；使用 AppAuth BrowserSelector 选择系统浏览器，并以 ACTION_VIEW 打开请求。应用接收原始回调，通过门禁后用 AppAuth 解析、兑换和显式刷新，不在授权模块访问 Graph。
- 应用依赖容器持有唯一授权协调器，OneDriveAuthorizationViewModel 复用它并串行调用；ViewModel 清理不能关闭进程共享 AppAuth 组件。协调器使用同一 mutex 串行浏览器回调与后端刷新；不会在重绘／恢复首页时重新登录或刷新。浏览器返回未完成时提供显式取消，十分钟过期；兑换／刷新最多一分钟，显示分类错误和重试入口。页面使用静态文字、显式分页和无动画控件。
- 待处理 AuthorizationRequest（含 state 与 PKCE verifier）和 AuthState 共存于加密 envelope。使用 elapsedRealtime 与系统 boot count 约束有效期，进程重建可恢复、设备重启作废。门禁检查完整地址、唯一 state 和唯一 code/error；无待处理或重复结果不兑换。兑换前原子保存消费与 interrupted 标志，进程中断后要求重新登录，不重兑旧代码。
- EncryptedAuthStateStore 使用 Android Keystore 不可导出 AES-256-GCM 密钥、随机 96-bit IV、认证标签和应用／存储域 AAD；AtomicFile 写入 noBackupFilesDir/onedrive-auth/state.bin，所有磁盘 I/O 运行于 IO dispatcher。恢复失败只清理授权存储，不修改本地目录配置或书籍副本；恢复时校验配置身份，防止复用旧注册。
- 日志仅包含随机操作 ID、阶段和固定错误分类；debug 输出调试阶段，release 仅输出失败。不得传递原始异常／响应给日志或界面，不启用 AppAuth 内容调试日志。协议实现依据 [AppAuth](https://github.com/openid/AppAuth-Android) 与 [微软授权码流程](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)。
- `backendAccessToken(forceRefresh)` 仅供可信存储后端使用：返回尚有效的现有 access token，过期时刷新，401 重试可强制刷新；失败返回 null 并通过固定 LoginIssue 分类，取消传播。不创建第二份 token 存储，调用者不得持久化或日志输出 token。`sessionId()` 返回非敏感登录代次 UUID，与 AuthState 共存于原加密 envelope；成功新登录更换、刷新保持、恢复保留，需重新登录时不返回它。旧授权 envelope 首次恢复迁移并持久化代次。
- 凭据不可进入普通配置、书籍 provider、日志或备份；错误不能包含原始 OAuth 响应、完整回调 query 或属性值。

## 配置验证

- 根 `scripts/verify-oauth-config.py` 在被忽略的 `.oauth-verification/` 内创建独立工程副本，只用虚构注册标识，不覆盖用户配置；只继承 SDK 定位，不复制用户 OAuth 属性，不更新依赖锁。
- 核对两变体的 generated BuildConfig、merged Manifest 和 APK，覆盖缺配置、部分配置、合法的独立回调、非法 URI、重叠范围和安全字符串生成；结果与脱敏日志留在临时目录，不提交副本或产物。
- 真实浏览器、回调及进程重启后的令牌恢复须步骤 06 真机验收，配置矩阵不能证明微软注册或真实登录有效。

- 源任务通过 `OneDriveAuthorizationSession` 携带预期登录代次；`backendAccessToken(expectedSession=...)` 在同一授权互斥锁内核对代次、刷新并取得令牌。旧任务不能在并发重新登录后取用新账号令牌，代次不匹配不刷新、不使新登录失效。

- `accountSubject(expectedSession)` 返回成功登录时由 AppAuth 从 ID token 解析出的稳定 subject，并在同一加密 envelope 内随 session 保存；刷新响应可省略 ID token，因此不能只依赖最新 TokenResponse。刷新保持 subject，新登录重新取值（缺失时清空），旧授权可从已有 AppAuth 状态迁移；不输出 subject 或原始 token，不新增 OAuth 范围。
