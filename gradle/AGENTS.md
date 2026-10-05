# Gradle 配置维护

本文件适用于 `gradle/`；同时遵循根 [AGENTS.md](../AGENTS.md) 和 Android 模块的 [构建约束](../app/AGENTS.md)。

- 根 `gradle.properties` 限制构建资源：worker 上限 4、项目并行沿用 Gradle 默认值（不显式设置）、构建 JVM `ActiveProcessorCount=4`、堆上限 2 GiB。日常与配置矩阵验证均继承这些限制，不同时启动多组构建。临时覆盖方法见 [app/README.md](../app/README.md#构建)。
- 直接依赖及插件版本集中在 `libs.versions.toml`，不得使用动态版本、SNAPSHOT 或预发布版本。仓库限于 Google Maven、Maven Central 和必要的 Gradle 插件仓库。
- 固定插件版本；应用及测试的实际传递依赖由 `app/gradle.lockfile` 锁定。不要手改生成的锁文件或关闭严格锁定来消除解析失败。
- 有意更新版本时，从项目根目录运行以下命令，解析应用／测试 compile/runtime 依赖图，并由构建和 lint 记录实际使用的工具配置：

    ```bash
    ./gradlew :app:resolveLockedDependencies :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease --write-locks
    ```

- 审阅版本与锁文件后，使用同一组任务、不带 `--write-locks` 复验。版本调整须有兼容依据和实际验证，结果保存在对应验证记录中。
- Wrapper 固定分发 URL 和 SHA-256，一起维护脚本、JAR 和 properties；保留 `gradlew` 可执行位，以及 `.gitattributes` 的 LF／CRLF 约定。日常构建使用项目 Wrapper，容器自带 Gradle 仅用于初始化 Wrapper。
