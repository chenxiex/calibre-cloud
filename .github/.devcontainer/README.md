# Android 开发容器

本配置提供命令行编译、静态检查、SQLite 数据检查和 ADB 工具，适用于 Kotlin + Jetpack Compose + AppAuth-Android + OkHttp + 原生 SQLite + WorkManager/FileProvider。初始化容器不会创建工程、下载应用依赖或执行应用构建；应用工程的构建说明见 [Android 工程](../../app/README.md#构建)。

## 镜像内的工具

| 工具 | 基线 | 用途 |
| --- | --- | --- |
| Debian Dev Containers 基础镜像 | `mcr.microsoft.com/devcontainers/base:2-trixie` | Git、非 root 用户 `vscode`、sudo 和常用终端工具 |
| OpenJDK | 21 | Android/Gradle 构建，包含在镜像内 |
| Gradle | 9.3.1 | 初始化项目及生成 Wrapper；发行包 ZIP 同时作为缓存种子 |
| Android Command-line Tools | 下载构建号 `15859902` | `sdkmanager`、`avdmanager`、`apkanalyzer` 等工具 |
| Android SDK Platform | API 36 | 应用工程的 `compileSdk = 36` |
| Android Build Tools | 36.0.0 | `aapt2`、`apksigner`、`zipalign` 等工具 |
| Android Platform Tools | 构建镜像时的稳定版本 | `adb`，在日常创建容器时不重新下载 |
| `sqlite3`、`jq`、`curl` | Debian 仓库版本 | 检查 Calibre 的 `metadata.db` 和 API 响应 |
| Python 3、`rsync`、`ripgrep`、`shellcheck`、`zip`、`unzip` | Debian 仓库版本 | 初始化缓存、搜索和脚本检查 |

基础镜像已提供 `vscode` 用户、sudo，以及 `ca-certificates`、`curl`、`git`、`jq`、`rsync`、`zip`、`unzip`，Dockerfile 仅安装额外依赖，参考[基础镜像内容清单](https://github.com/devcontainers/images/blob/main/src/base-debian/history/2.2.1.md)。构建结束前切换为 `vscode`，由该用户创建缓存目录，无需额外修改目录所有者。

镜像使用 [Debian trixie 提供的 OpenJDK 21](https://packages.debian.org/trixie/openjdk-21-jdk-headless)。[AGP 9.1.1 官方兼容表](https://developer.android.com/build/releases/agp-9-1-0-release-notes)要求 Gradle 至少 9.3.1、JDK 至少 17 和 Build Tools 至少 36.0.0；JDK 21 满足最低要求，也在 [Gradle 支持范围](https://docs.gradle.org/current/userguide/compatibility.html#java_runtime)内。AGP、Kotlin、Compose 和应用库的实际版本仍由项目 Gradle 配置决定。Compose 编译器插件应与 Kotlin 版本匹配，参考 [Compose 配置说明](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)。

运行 Gradle 的 JDK 与应用的 Java/Kotlin 编译目标分别配置。应用工程显式设置 Java 兼容级别及 Kotlin JVM target，具体约束见 [Android 模块约束](../../app/AGENTS.md#构建与实现)，参考 [Android 构建中的 Java 版本说明](https://developer.android.com/build/jdks)。

Command-line Tools 和 Gradle 的归档使用固定下载地址并校验 SHA-256，校验值来自 [Android 官方下载表](https://developer.android.com/studio#command-line-tools-only)和 [Gradle 官方 checksum 文件](https://services.gradle.org/distributions/gradle-9.3.1-bin.zip.sha256)。基础镜像标签、系统软件包和 `platform-tools` 会随上游更新，因此重新构建镜像不保证逐字节相同。构建步骤会通过 `sdkmanager --licenses` 接受 [Android SDK 许可](https://developer.android.com/studio#terms-and-conditions)。

## 持久缓存

工作区由 [Dev Containers 自动挂载](https://code.visualstudio.com/remote/advancedcontainers/change-default-source-mount)，无需写死容器路径；项目源码、项目级 `.gradle` 目录和构建产物留在工作区。四个 named volume 分别保存以下内容：

| Volume 名称 | 容器挂载位置 | 保存内容 |
| --- | --- | --- |
| `calibre-cloud-${devcontainerId}-gradle` | `/home/vscode/.gradle` | Gradle Wrapper 发行包、AGP/Kotlin/应用库、构建缓存、Gradle 下载的 JDK |
| `calibre-cloud-${devcontainerId}-sdk` | `/home/vscode/.local/share/android-sdk` | 可写 SDK，以及开发过程中安装的额外平台、工具或系统镜像 |
| `calibre-cloud-${devcontainerId}-android` | `/home/vscode/.android` | ADB 配对密钥、调试签名、AVD 配置等 Android 用户数据 |
| `calibre-cloud-${devcontainerId}-cache` | `/home/vscode/.cache` | 遵循 `XDG_CACHE_HOME` 的工具缓存 |

同一工作区和配置使用稳定的 `${devcontainerId}`。删除或重新创建容器不会删除这些 volume；移动工作区、切换容器运行时或主动删除 volume 后，需要重新初始化缓存。Gradle 也会按自身保留策略清理长期未使用的缓存。

`JAVA_HOME`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`ANDROID_USER_HOME`、`GRADLE_USER_HOME` 和 `XDG_CACHE_HOME` 已在镜像设置。初始化脚本会修正 volume 的 UID/GID，兼容 Linux 上 Dev Containers 将 `vscode` 映射为宿主用户的行为。

SDK 种子位于镜像的 `/opt/android-sdk-seed`，可写 SDK 位于 volume 中，两者路径不同。`post-create.sh` 只复制完整的缺失 package，并通过临时目录完成复制后再移入目标位置；已安装或升级的 package 会保留。这样不会把镜像文件与 volume 中的新版工具混在同一个 package 中。未来修改 SDK 基线并重建时，新增平台和 Build Tools 会自动补入；相同路径的工具升级需要显式运行 `sdkmanager`。

初始化时还会逐行合并镜像和 volume 中已接受的 SDK 许可 hash，保留开发期间接受的记录，同时补入镜像新增的记录。

Gradle 的已校验 ZIP 会复制到默认 Wrapper 缓存位置。对于 `https://services.gradle.org/distributions/gradle-9.3.1-bin.zip`，Wrapper 首次使用时直接从本地 ZIP 校验、解压，无需再次下载。初始化脚本不伪造 Wrapper 的 `.ok` 完成标记。缓存布局按 [Gradle 9.3.1 PathAssembler](https://github.com/gradle/gradle/blob/v9.3.1/platforms/core-runtime/wrapper-shared/src/main/java/org/gradle/wrapper/PathAssembler.java)和 [Install](https://github.com/gradle/gradle/blob/v9.3.1/platforms/core-runtime/wrapper-shared/src/main/java/org/gradle/wrapper/Install.java) 实现；使用其他版本、镜像 URL、`-all` 发行包或自定义 Wrapper 缓存路径时，首次仍需下载，之后保存在 volume 中。

## 使用

宿主机需要 Docker 和支持 Dev Containers 的编辑器。`.github/.devcontainer` 中的配置用于构建开发镜像；可通过 Dev Containers CLI 显式选择此配置：

```bash
devcontainer build --workspace-folder . --config .github/.devcontainer/devcontainer.json
```

编辑器使用仓库根目录 `.devcontainer/devcontainer.json` 中的 `image` 配置连接预构建镜像。构建上下文默认是配置所在的 `.github/.devcontainer`，通过 `.dockerignore` 只纳入 Dockerfile 和所需的初始化脚本。工作区挂载、`remoteUser` 和 UID 映射使用[基础镜像元数据及 Dev Containers 默认行为](https://github.com/devcontainers/spec/blob/main/docs/specs/devcontainerjson-reference.md)。构建时将两个初始化脚本复制到镜像的 `/usr/local/share/calibre-devcontainer/`，`postCreateCommand` 使用 `/usr/local/share/calibre-devcontainer/post-create.sh`，不依赖工作区中的配置目录位置。修改脚本后需重新构建镜像。

容器固定使用 `linux/amd64`，因为本配置中的 Google Linux SDK 原生工具使用 x86-64。ARM 宿主机需要容器运行时支持 amd64 模拟。配置使用非 root 用户开发，自动等待缓存初始化结束后再连接编辑器。

VS Code 配置包含 Kotlin 语法支持、Java 和 Gradle 扩展。Java 的 `linux-x64` 平台扩展自带语言服务器运行时，构建 JVM 固定为镜像中的 JDK 21，参考 [扩展官方说明](https://github.com/redhat-developer/vscode-java#java-tooling-jdk)。这些扩展提供基础编辑支持；Compose Preview、Layout Inspector 等 Android Studio 功能需要在宿主机使用 Android Studio。

仓库已包含项目 Wrapper，维护规则见 [Gradle 配置维护](../../gradle/AGENTS.md)。镜像里的 Gradle 仅用于初始化或重新生成匹配版本的 Wrapper：

```bash
gradle wrapper \
    --gradle-version 9.3.1 \
    --distribution-type bin \
    --gradle-distribution-sha256-sum b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06
```

`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar` 和 `gradle-wrapper.properties` 一起纳入版本控制。日常构建使用项目 Wrapper，参考 [Gradle 官方指南](https://docs.gradle.org/current/userguide/gradle_wrapper.html)：

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

Compose、AppAuth-Android、OkHttp、WorkManager、AndroidX Core 等通过项目 Gradle 添加，下载结果自动进入 Gradle volume。原生 SQLite 使用 Android SDK 提供的 API，FileProvider 由 AndroidX Core 提供。无需在容器中通过额外包管理器安装这些应用库；镜像中的 `sqlite3` 仅用于开发期间检查数据库。

如果工作区已有宿主机生成的 `local.properties`，其中的 `sdk.dir` 会覆盖 SDK 环境变量。容器使用时应移除该项，或在这个被 Git 忽略的文件中设置 `sdk.dir=/home/vscode/.local/share/android-sdk`。

## 扩展开发依赖

开发过程中安装额外 SDK package，下载和安装都会留在 SDK volume：

```bash
sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-35" "sources;android-36"
```

升级 SDK 中相同路径的工具也会保留到下一次创建容器：

```bash
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "cmdline-tools;latest"
```

长期使用的系统工具应添加到 Dockerfile 的安装列表，再重建镜像。Gradle 管理的应用依赖应在项目配置中固定版本。更新镜像中的 Gradle 或 Command-line Tools 时，同时更新下载版本和对应 SHA-256；更改 Wrapper 种子的生成方式时，应重新核对相应 Gradle 版本的缓存布局。

## 真机调试

默认镜像不包含 Android Studio GUI、Android Emulator 和系统镜像。日常调试可使用 Android 11 及以上真机的无线调试，或宿主机上的模拟器。容器内已经提供 `adb`。

启用手机“开发者选项 → 无线调试”，打开“使用配对码配对设备”。在容器终端运行下列命令，替换示例地址和端口，并在提示时输入配对码：

```bash
adb pair 192.168.1.10:37123
adb connect 192.168.1.10:42157
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

配对端口与连接端口不同，分别读取手机上的配对页面和无线调试主页面；它们也可能在下次启用无线调试时变化。容器需要能访问手机所在网络，可显式使用 IP 和端口连接，参考 [ADB 无线调试说明](https://developer.android.com/tools/adb#wireless-android11-command-line)。

如果改为在容器内运行模拟器，可通过 `sdkmanager` 将 `emulator` 和系统镜像安装到 SDK volume，AVD 数据保存在 Android 用户数据 volume。Linux 上的硬件加速通常还需要额外映射 `/dev/kvm` 并配置权限；USB 真机调试也需要单独配置设备访问。

## 验证记录

容器持久挂载、SDK、Wrapper 种子及应用构建的实际结果见 [第一阶段验证记录](../../app/verification/phase-1.md)，ADB 真机操作结果见 [第二阶段验证记录](../../app/verification/phase-2.md)。
