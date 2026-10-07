# 第三阶段验证记录

范围以 [spec.md](../../spec.md) 第三阶段“图书馆与打开”为准。本记录保存实际执行结果；自动检查通过不等于用户共同验收通过，未执行的路径不记为通过。

## 步骤 01：本地图书馆查询与格式解析（2026-10-07）

对应 R13、R22–R25 的查询语义，R10 的代表书籍选择，R27 的展示数据，R32。实现位于 `library/` 包，规则见 [查询约束](../src/main/java/io/github/chenxiex/calibrecloud/library/AGENTS.md)；本步没有正式页面，页面验收从步骤 02 开始。

### 自动检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

结果 `BUILD SUCCESSFUL`（1m 28s），**106 tests、0 failures、0 errors、0 skipped**，其中新增 `LibraryIndexTest`（12 项）与 `LibraryQueryServiceTest`（2 项）。之后为去除冗余把“作者缺失／大小未知”一项并入默认格式测试，`LibraryIndexTest` 为 11 项；`library` 包 JVM 测试复跑 13 项全部通过，全量基线未重跑，上述总数不更新为通过记录。lint 无 error，5 条均为既有 warning，没有指向 `library/` 的提示。

JVM 测试覆盖：多关键词“且”与跨字段、字段限定、中文包含、字面匹配（通配符与表达式不被解释）、失效／不支持栏目；下载／已读／格式三维筛选的“或／且”；已下载仅计格式筛选范围内的完整副本，其它库副本不生效；已读栏目未配置／失效／类型改变时筛选返回原因且已读值为空；标签多值文件夹、无标签兜底、空结果不生成兜底、文件夹名称序与最新代表书籍；丛书序号升／降序且缺序号在后；标题／加入时间／评分排序、空评分在后、同值稳定次序；分页切片、总数与末页；默认格式优先级、缓存优先、源格式回退、无格式原因、未知大小与作者保持为空、源缺失标识；新代次或栏目配置变化后旧请求返回 `Stale`；服务在无完整导入时返回 `NO_METADATA` 且不读取清单，索引按版本只构建一次。查询输入只有导入索引与清单，没有后端或任务依赖。

### 真机平台测试

设备 `192.168.0.72:41673`（无线调试）。安装前用 `apkanalyzer` 核对 debug application ID 为 `io.github.chenxiex.calibrecloud.debug`；`connectedDebugAndroidTest` 因 `androidTestUtil` 依赖锁无锁状态无法运行（未写锁），改用既有方式安装 APK 并执行：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class io.github.chenxiex.calibrecloud.library.LibraryQueryPlatformTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

首次执行 `OK (4 tests)`，用时 139.9 s，其中一项为 3000 本合成大样本，几乎没有断言、只记录耗时（一次搜索加一次文件夹查询 1242 ms，仅作参考），耗时占了绝大部分。有了下文的 286 本真实库后，它不再提供独立证据，已删除；分页与稳定排序的正确性由 JVM 测试覆盖，规则与行数无关。现为 3 项，按 Calibre 9.14.0 布局 fixture 经真实导入后：文本／枚举／多值文本栏目的分类与搜索、计算栏目报告 `CATEGORY_COLUMN_INVALID`、未配置已读栏目时各行无已读值；保存已读栏目后旧请求变为 `Stale` 并以真实导入值筛选；真实清单驱动下载状态，清除元数据后查询返回 `NO_METADATA` 而清单保留。验证后已卸载 debug 与测试包。

### 扩展库真机查询（286 本）

`ExtendedLibraryQueryTest` 在真机上用 `assets/calibre-extended/generate.py` 本次重新生成的库（286 本，`integrity_check` 为 ok）验证查询。库文件不进入仓库：测试通过 instrumentation 参数 `extendedLibraryDb` 取得设备上的路径，经 shell 读入应用缓存后真实导入，期望值全部由同一文件上的 SQL 计算，没有硬编码数值；不带该参数时 8 项均跳过（已确认 `skipped="8"`、构建成功）。

```bash
adb push <生成目录>/metadata.db /data/local/tmp/extended-metadata.db
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=io.github.chenxiex.calibrecloud.library.ExtendedLibraryQueryTest \
  -Pandroid.testInstrumentationRunnerArguments.extendedLibraryDb=/data/local/tmp/extended-metadata.db
adb shell rm /data/local/tmp/extended-metadata.db
```

设备 PA6（Android 14）上 **8 tests、0 failures、0 errors、0 skipped**，约 41 s（精简平台测试后与 `LibraryQueryPlatformTest` 3 项同次复跑：8＋3 项全部通过，扩展库部分 39.4 s，平台部分 1.5 s），`BUILD SUCCESSFUL`；Gradle 自动卸载 debug 与测试包，设备包列表无 `calibrecloud`，已删除推送的库文件。覆盖：

- 每种排序（默认加入时间、标题、评分）下 20 本分页拼接与一次取全量逐项相同，每本书恰好一次，末页 6 本，总数 286；评分升／降序缺失值都在末尾，标题按 Collator 有序，加入时间降序。
- 标签、丛书、`#topic`（多值文本）、`#shelf`（枚举）四种分类：文件夹名称集合、各文件夹书籍数、无值文件夹置末、名称序、代表书籍为文件夹内最新加入的书均与 SQL 一致；进入文件夹的总数一致；计算栏目 `#formula` 返回 `CATEGORY_COLUMN_INVALID`；丛书文件夹内按含小数的丛书序号升序。
- 已读筛选以配置的 `#read_status` 为准：已读与 SQL 中“是”一致，未读为“否”加空值（R13：空值／否视为未读），两者并集为全库，配置有效时所有行都有已读值。
- 搜索：标题、作者、标签、丛书、枚举栏目的限定搜索与 SQL 子串匹配一致；全库搜索包含标签匹配；通配符 `%` 与 SQL 片段按字面处理无结果；有搜索词时分类视图展平为书籍。
- 行数据：默认格式为 EPUB，否则按名称序的最前格式，大小等于 `data.uncompressed_size`；无格式的书带 `NO_FORMAT`；作者缺失的书作者列表为空；封面标志与 `has_cover` 一致；PDF 格式筛选与 SQL 一致。

首次编写时已读筛选的期望把空值当作“既非已读也非未读”，与 R13 不符，实际行为符合规格，已改正测试，没有改动生产代码。

记录到的耗时（`LibraryQueryReference` 日志，两次运行）：含索引构建的首次查询 640／694 ms；缓存索引后单次查询约 搜索 50–75 ms、标签文件夹约 60 ms、标题排序 78–82 ms、已读筛选 46–79 ms。这是不含界面的纯查询耗时。AC06 的页面、分页与旋转等界面部分留给步骤 02 起的真机验收。

### 未完成与需要用户准备

- 扩展测试书库：已由 `assets/calibre-extended/generate.py`（见其 [README](../../assets/calibre-extended/README.md)）使用开发容器内 Calibre 9.14.0 的 Python API 基于仓库样本副本生成，共 286 本，`PRAGMA integrity_check` 与 `calibredb check_library` 均无问题；同一脚本两次生成的书籍数据与全部书籍／封面文件逐字节一致。用户已在桌面 Calibre 中确认能够正常加载（2026-10-07）。该库不含“丛书序号缺失”与“格式大小未知”（Calibre 以 NOT NULL 约束禁止），这两种边界只由合成测试覆盖；“已读为空”“作者缺失”由生成后的 SQL 构造，不是桌面程序自然产生的状态；其中“已读为空”在真机查询中按 R13 视为未读。真机验证使用的栏目失效（改名／删除 `#retired`）由用户在桌面副本上执行。库包含 286 本，已用于上面的真机查询记录，但仍为合成内容。
- 栏目失效场景（改名或删除 `#retired`）尚未在真机上执行：测试只验证了计算栏目与不支持类型的失效报告，真正的“桌面改动后重新导入”仍待用户在桌面副本上准备。

## 依赖锁维护：设备测试运行器

步骤 01 真机执行时 `connectedDebugAndroidTest` 被严格锁定拒绝，当时改用 `adb install` 加 `am instrument` 绕过，等同于回避锁定，随后单独维护解决。缺失的锁状态依次是 `androidTestUtil`（无声明依赖，仅记入 `empty=`）与 13 个 AGP `_internal-unified-test-platform-*` 配置（Unified Test Platform 主机端运行器，含 gRPC、Netty、Protobuf、Guava 等 149 个新模块坐标）。

- `app/build.gradle.kts` 的 `resolveLockedDependencies` 增加这些配置，使文档中的标准写锁命令可复现覆盖它们。
- 执行 `gradle/AGENTS.md` 的写锁命令后审阅锁文件差异：应用、单元测试、AndroidTest、lint 与 release 的既有配置没有新增条目或版本变化；原有模块行只追加了 UTP 配置名。新增条目只属于 `androidTestUtil`／UTP 配置。
- 不带 `--write-locks` 重跑同一组任务为 `BUILD SUCCESSFUL`，锁文件逐字节不变；`connectedDebugAndroidTest` 通过锁解析，在无连接设备时以 `No connected devices!` 失败。随后设备连接，`connectedDebugAndroidTest` 在 PA6（Android 14）上运行 `LibraryQueryPlatformTest`，`BUILD SUCCESSFUL`，4 tests、0 failures、0 skipped，约 35 s（当时仍含后来删除的 3000 本合成项）；设备端包由 Gradle 自动卸载，运行后锁文件无变化。上文扩展库的 8 项测试也通过同一任务执行。
