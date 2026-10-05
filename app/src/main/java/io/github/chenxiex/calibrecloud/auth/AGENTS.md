# OneDrive 授权开发约束

本目录遵循根与 [Android 模块](../../../../../../../../AGENTS.md) 约束，产品行为以 R08、R31、R33–R35 为准。面向开发者的注册与配置指引见 [app/README.md](../../../../../../../../README.md#onedrive-配置)。

## 构建配置

- 只读取根 `local.properties` 中约定的三项 OAuth 属性；三个值全部非空才启用，两变体接收范围须隔离。不读取或打包 client secret，不更改 `sdk.dir` 等已有属性。
- 缺配置或部分配置时，两变体仍须可构建并使用本地授权；禁用回调接收器，使用各变体独立的禁用 scheme，生成的 client ID 与回调值均为空，不打包部分配置。
- 完整配置非法或范围重叠时，构建失败并指出相应属性，不输出属性值。使用结构化 URI 解析与安全的生成字符串，不将属性直接拼接为代码。
- 回调须为小写自定义 scheme、非空绝对字面路径，可带小写字面 host；拒绝 HTTP(S)、文件／内容等保留 scheme、凭据、端口、query、fragment、通配 host、转义路径和 XML 特殊字符。
- 构建校验、Manifest placeholders 和运行配置共用解析结果；运行配置由 BuildConfig 生成，不维护第二套 scheme 常量。

## 回调接收与地址验证

- `app/oauth-manifests/` 提供带 host 和无 host 的字面模板，由变体 sourceSet 选择；必须整体替换 AppAuth receiver，避免遗留宽泛 filter。无 host 时省略 host/path 属性，不生成空属性。
- 带 host 时按 scheme、host 和精确 path 匹配；无 host 的 `scheme:/oauth2redirect` 须正确支持，但 Android 忽略其路径 filter，因此同 scheme 的任意另一变体接收范围都会重叠，必须拒绝。同 scheme 仅在双方都有 host 且 host 或精确 path 不同的情况下允许。规则依据见 [Android data 元素](https://developer.android.com/guide/topics/manifest/data-element)。
- 完整回调校验由 `OneDriveOAuthConfiguration.acceptsCallback` 承担，包含无 host 的路径检查；OAuth 返回的 query 留待协议流程处理。后续协调器处理结果前还必须核对待处理事务、state 与重复交付，不能把地址匹配视为已授权。

## 授权阶段与隐私

- 当前只准备全球个人账号 `consumers` 端点、授权码、S256 PKCE 与 `openid`、`offline_access`、`https://graph.microsoft.com/Files.ReadWrite` 范围，不启动浏览器，不请求 Graph，不存储 token，也不构造假登录状态。
- 后续用 AppAuth 生成随机 PKCE verifier 和 state、执行兑换与刷新，并实现令牌安全存储；遵循 [AppAuth](https://github.com/openid/AppAuth-Android) 与 [微软授权码流程](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)。
- 凭据不可进入普通配置、书籍 provider、日志或备份；错误不能包含原始 OAuth 响应、完整回调 query 或属性值。

## 配置验证

- 根 `scripts/verify-oauth-config.py` 在被忽略的 `.oauth-verification/` 内创建独立工程副本，只用虚构注册标识，不覆盖用户配置；只继承 SDK 定位，不复制用户 OAuth 属性，不更新依赖锁。
- 核对两变体的 generated BuildConfig、merged Manifest 和 APK，覆盖缺配置、部分配置、合法的独立回调、非法 URI、重叠范围和安全字符串生成；结果与脱敏日志留在临时目录，不提交副本或产物。
- 真实浏览器、回调及令牌恢复仍须后续真机验收，配置矩阵不能证明微软注册或真实登录有效。
