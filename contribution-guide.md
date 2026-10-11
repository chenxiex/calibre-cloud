# 贡献指南

本文面向参与 Calibre Cloud 开发的贡献者和 fork 维护者。应用介绍与使用方法见 [README](README.md)。

## 开发环境与项目文档

开发环境配置及缓存、SDK 扩展、设备连接方法见 [.github/.devcontainer/README.md](.github/.devcontainer/README.md)。Android 工程的构建与产物说明见 [app/README.md](app/README.md)，实际验证结果见 [第一阶段验证记录](app/verification/phase-1.md)、[第二阶段验证记录](app/verification/phase-2.md)、[第三阶段验证记录](app/verification/phase-3.md)、[第四阶段验证记录](app/verification/phase-4.md) 和 [第五阶段验证记录](app/verification/phase-5.md)（首版验收汇总）。后台任务与启动同步使用方法见[Android 工程说明](app/README.md#任务与后台)，步骤 09 验收流程与结果见[步骤 09 指南](app/verification/step-09-device-guide.md)。

- [spec.md](spec.md)：已通过用户验收的首版需求、模块契约、状态与验收条件，是后续开发的唯一需求与验收基线。
- [AGENTS.md](AGENTS.md)：轻量 SDD 流程、协作、文件写入和验证约定。
- [questions.md](questions.md)：当前已确认结论，以及后续需要填写的阻塞问题。
- [Android 真机操作技能](.agents/skills/android-device-verification/SKILL.md)：可复用 ADB helper、页面流程与轻量设备操作 agent。

## OneDrive 应用注册

首版只支持全球服务的个人 OneDrive 账号。开发者及 fork 维护者使用自己的微软应用注册；本地后端不需要这些配置。Android 工程已按变体读取以下属性并生成回调配置；已接入个人账号浏览器授权，真实登录验收状态见验证记录。详细校验和构建矩阵见 [工程配置说明](app/README.md#onedrive-配置)。

1. 在 Azure 门户的 Microsoft Entra ID／[Entra 管理中心](https://entra.microsoft.com/)进入“应用注册 → 新注册”，选择“仅个人 Microsoft 账号”，注册后记录“应用程序（客户端）ID”。需要具备所选租户的应用注册权限，具体步骤见[微软注册说明](https://learn.microsoft.com/en-us/entra/identity-platform/quickstart-register-app)。
2. 为正式版和 debug 版选择各自独立、属于自己的回调 scheme，例如 `org.example.calibrecloud://auth/oauth2redirect` 与 `org.example.calibrecloud.debug://auth/oauth2redirect`。将示例前缀换成自己的值，在“身份验证 → 添加平台 → 移动和桌面应用”中登记两个自定义重定向 URI。本项目使用 AppAuth，因此选择“移动和桌面应用程序”，而不是要求包名／签名哈希的“Android”平台。注册值须与构建配置完全一致。示例采用门户提示要求的 `customScheme://` 格式，其中 `auth` 是 URI 的 host，`/oauth2redirect` 是路径，不需要部署网站；标准允许的无 host 单斜杠格式 `scheme:/oauth2redirect` 会被当前门户输入校验拒绝，不用于这里的注册示例。见[微软回调平台说明](https://learn.microsoft.com/en-us/entra/identity-platform/how-to-add-redirect-uri)。
3. 在“API 权限”添加 Microsoft Graph 的委托权限 `Files.ReadWrite`，供目录访问和已读写回使用；不用应用程序权限或 `Files.ReadWrite.All` 扩大范围。应用授权请求使用 `openid`、`offline_access` 和 `https://graph.microsoft.com/Files.ReadWrite`。文件写入权限参见[Graph 权限说明](https://learn.microsoft.com/en-us/graph/api/driveitem-createuploadsession?view=graph-rest-1.0)，刷新令牌范围参见[授权码流程](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)。
4. 在项目根目录的 `local.properties` 中添加以下属性，保留已有的 SDK 配置。该文件已被 Git 忽略；示例 client ID 必须换成注册结果。

    ```properties
    onedrive.clientId=00000000-0000-0000-0000-000000000000
    onedrive.redirectUri=org.example.calibrecloud://auth/oauth2redirect
    onedrive.debugRedirectUri=org.example.calibrecloud.debug://auth/oauth2redirect
    ```

5. 工程按构建变体读取相应 URI，同时配置应用回调接收范围；浏览器登录端点使用个人账号范围 `consumers`，采用授权码与 PKCE。Android 原生客户端不配置或打包 client secret；client ID 是注册标识，登录令牌由设备上的应用管理。见[微软原生授权流程](https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-auth-code-flow)。真实登录回调与目录选择的验收结果见验证记录；专门测试书库的写回已在第四阶段于独立 debug 包中验证，结果见[第四阶段记录](app/verification/phase-4.md)。

三项属性未配置时，工程仍可构建并使用本地后端，OneDrive 入口显示配置指引。正式版与 debug 版必须分别匹配已注册回调，避免测试包接收正式版的授权回调；构建和 ADB 方法仍见开发容器文档。

## CI 与发布

[ci.yml](.github/workflows/ci.yml) 对每次分支 push 和 PR 运行单元测试、`lintDebug` 并构建 debug APK，上传为 workflow artifact。[release.yml](.github/workflows/release.yml) 在推送 `v*` 标签（须与 `versionName` 一致）时构建签名 release APK 并发布到 GitHub Release。

GitHub secret 名不能含点号，因此把 `local.properties` 的键转为大写下划线（由 [write-local-properties.py](.github/scripts/write-local-properties.py) 生成）：`release.storePassword`→`RELEASE_STORE_PASSWORD`，`release.keyAlias`→`RELEASE_KEY_ALIAS`，`release.keyPassword`→`RELEASE_KEY_PASSWORD`，`onedrive.clientId`→`ONEDRIVE_CLIENT_ID`，`onedrive.redirectUri`→`ONEDRIVE_REDIRECT_URI`，`onedrive.debugRedirectUri`→`ONEDRIVE_DEBUG_REDIRECT_URI`。`release.storeFile` 对应的密钥库文件以 base64 存入 `RELEASE_STORE_FILE_BASE64`（`base64 -w0 your.jks`）。OneDrive 三项缺省时构建仍可进行，只是登录回调保持禁用。

## README 截图

[README](README.md) 中的界面截图位于 [docs/screenshots/](docs/screenshots/)，在 PA6 上用独立 debug 包和示例测试书库副本截取，并裁掉了系统状态栏。更新截图时同样只使用 debug 包和测试书库副本，截图完成后卸载 debug 包。
