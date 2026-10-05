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
- [任务约束](src/main/java/io/github/chenxiex/calibrecloud/tasks/AGENTS.md)：固定请求、来源优先级、依赖、新鲜度及提交／刷新证据。
- [文件提供约束](src/main/java/io/github/chenxiex/calibrecloud/files/AGENTS.md)：私有文件代次、FileProvider 范围和临时只读授权。

## Manifest 与入口

- debug 使用 `.debug` application ID 后缀和可辨认名称，AndroidTest 使用独立测试包。provider authority 均由 `${applicationId}` 派生；书籍 provider 使用 `${applicationId}.books`，只在完整副本读取契约实现时开放。
- OAuth 配置与回调地址验证见 [授权模块](src/main/java/io/github/chenxiex/calibrecloud/auth/AGENTS.md)。缺少完整配置时回调接收器显式禁用，使用各变体独立的 `.disabled` scheme；不能阻止本地构建、发起登录或显示虚构成功。
- 当前入口已接入本地目录授权，开发约束见 [本地授权](src/main/java/io/github/chenxiex/calibrecloud/storage/local/AGENTS.md)。本地入口只检查平台授权与私有配置；OneDrive 只访问 OAuth 授权端点，不访问源书库文件或 Graph 文件 API，不注册 worker 或周期任务。保留 WorkManager 默认 initializer 的移除配置，直到持久任务实现需要接入执行器。
- 只申请已实现路径需要的权限；检查依赖合并的 Manifest，不因声明依赖而开放网络、广泛存储或后台权限。网络入口使用 HTTPS。
- 凭据和授权数据不能进入备份／设备迁移；禁用备份并排除普通应用数据域；授权密文置于 noBackupFilesDir，使用不可导出的 Keystore AES-GCM 密钥，不能移至普通 files/cache。
- 应用控制的页面遵循 R20：不通过滚动遍历内容、不添加过渡或加载动画，状态在灰度下可辨认。静态占位入口使用文字和布局，避免默认点击效果；内容超出一页时采用显式分页。

## 验证与证据

- 测试随实际功能路径加入，不添加常量或空 Activity 的占位测试；`NO-SOURCE` 或生成 AndroidTest APK 不能记为功能测试通过。
- 工程与构建配置变更按受影响路径执行 debug/release 构建和 lint；生成依赖锁后再运行不更新锁的构建，核对锁文件未变化。
- 检查实际 APK 的最低系统、包标识、可调试状态及导出组件；OAuth 未接入时核对回调禁用。release 仅生成未签名产物，不使用正式签名。
- ADB 安装前先从 APK 核对 debug application ID，只使用测试书库副本；记录结果后卸载 debug 与测试包，不覆盖正式应用或访问正式数据。
- 实际命令、结果和未完成的验收写入 [verification/phase-1.md](verification/phase-1.md)。构建或自动测试不能替代对应阶段的真机验收。
