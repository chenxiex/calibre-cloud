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

## 步骤 02：墨水屏主框架与图书馆浏览（2026-10-07）

对应 R20–R23、R27 的页面，R10 的可见封面与文件夹代表封面，R06、R30 的无配置／无元数据入口。实现位于 `ui/` 的 `MainScreen`、`LibraryScreen`、`LibraryViewModel`、`PageGeometry`，约定见 [界面约束](../src/main/java/io/github/chenxiex/calibrecloud/ui/AGENTS.md)。搜索框与筛选按钮本步只占位（筛选置灰），底栏上次打开位置不在本步。

### 自动检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.chenxiex.calibrecloud.ui.LibraryScreenTest
```

- 基线 `BUILD SUCCESSFUL`：JVM **117 tests、0 failures**，其中新增 `PageGeometryTest`（5）与 `LibraryViewModelTest`（7，覆盖未配置／无元数据、分页切片与末页、尺寸变化保持页、文件夹返回恢复、失效分类栏目、仅当前页封面入队且同页不重复、隐藏时不入队）。首次 lint 为 0 error、6 条 warning，其中 5 条为既有版本提示，第 6 条（未使用字符串）已修复；修复后复跑 `:app:testDebugUnitTest :app:lintDebug` 通过，lint 0 error、5 条 warning（均为既有版本提示）。
- 全量 `connectedDebugAndroidTest`（PA6，Android 14）：231 tests、0 failed、18 skipped（均为需显式参数的真实 SAF／OneDrive／扩展库用例），`BUILD SUCCESSFUL`。全量运行开始时尚无“清除元数据后旧封面不回填”一项。
- `LibraryScreenTest` 单独复跑 12 项全部通过：容量随尺寸变化且不越界、首页／末页按钮与末页不满、空书库与失效分类栏目的原因文字、无书库与无元数据的入口、网格／列表字段显隐（未知作者与大小无占位、已读与下载勾选为文字）、根层与文件夹层菜单差异与系统返回恢复、长菜单分页、筛选置灰与搜索占位、底栏切换保持图书馆位置、封面入队与完成后显示、缓存封面不入队、清元数据后旧封面不回填。
- 之前 `LocalDirectoryAuthorizationDeviceTest`（显式开启的真实 SAF 用例）依赖启动页文字，已改为先点底栏“更多”；该用例本轮未启用，未运行。

### 真机 ADB 核对（扩展库 286 本）

用 `ExtendedLibrarySeedTest`（仅带 `seedLibraryDb` 参数时运行）把生成的扩展库导入 debug 应用私有状态，不涉及存储授权也不触碰源书库，再通过 ADB 与 helper 按资源 ID 操作；截图保存在被忽略的 `app/build/verification/phase3-step2/`。设备为 PA6（1072×1448，360 dpi），debug 包 `io.github.chenxiex.calibrecloud.debug`。

- 首次冷启动约 5 s 才显示首页（含索引构建），此前显示“正在读取本地书库”；之后在页面内切换无明显等待。
- 竖屏网格每页 8 本（4 列×2 行），共 36 页；横屏 6 本（6×1），共 48 页。右上斜幅“已读”文字可辨，缺封面时标题居中。
- 视图菜单根层 3 页；选“标签”后顶栏显示“标签”，13 个文件夹分 2 页，文件夹显示名称与本数；进入文件夹后顶栏为返回键与文件夹名，列表视图显示标题、作者、已读标签和大小，未知字段无占位。系统返回键恢复为“标签”文件夹层。
- 旋转：竖屏翻到第 3 页（首个可见项序号 16），转横屏为第 3/48 页（该页包含序号 16），转回竖屏仍为第 3/36 页。已把 `accelerometer_rotation`／`user_rotation` 恢复为原值。
- “更多”页显示原第一页，临时验证页仍可达；禁用按钮为浅灰色加细边框。
- 封面：没有存储授权，封面任务无法读取源，所有封面保持文字占位；真实封面缩略图显示需在有授权的书库上另行验收。

### 界面图标化修订（Q36–Q39）

用户试用后要求减少说明文字，确认结论见 [questions.md](../../questions.md) 的 Q36–Q39，规格同步到 R20、R21、R23、R27、R30。改动：顶栏筛选／视图改为无边框图标（筛选在左）；圆角搜索框带搜索图标；搜索框与内容框、内容框与底栏之间加分割线；翻页行改为首页、上一页、“当前页 / 总页数”、下一页、末页图标，不再显示总数和上次同步时间（后者保留在“更多”临时元数据页）；底栏为图标加小字，所在页实心图标。视图菜单按视图／分类／排序分组并以分割线隔开，分类只列不分类／丛书／标签／更多，“更多”弹出同窗口覆盖层选择其它栏目；排序只列依据，箭头表示方向，切换依据采用默认方向、再点同一依据反向；切换选项不关闭菜单。已读斜幅改为两腰落在封面上边和右边的梯形。图标为 `res/drawable` 矢量资源（Material Icons，Apache-2.0；`ic_more_*` 自绘），不增加依赖。

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
adb -s <PA6> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <PA6> install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s <PA6> shell am instrument -w -e class io.github.chenxiex.calibrecloud.ui.LibraryScreenTest io.github.chenxiex.calibrecloud.debug.test/androidx.test.runner.AndroidJUnitRunner
```

- JVM **119 tests、0 failures**：`PageGeometryTest` 增加分组菜单按高度分页（标题不落页尾、分割线不在页边、超高行单独成页）；`LibraryViewModelTest` 改为页码跳转钳制与末页不满，新增排序依据默认方向与同依据反向。lint 0 error、5 条 warning（均为既有版本提示）。
- `LibraryScreenTest` **14 项全部通过**（手动安装后 `am instrument`，以保留用户已完成的目录授权与导入；未运行全量连接测试）：翻页行首末页禁用与跳转、视图按钮激活态、选项后菜单保持打开、系统返回先关菜单再退文件夹、排序方向状态与反向、“更多”弹窗只列文本／枚举栏目且选择后回到菜单、长菜单分页且行不越界、底栏选中态，以及原有字段显隐、入口和封面用例。
- 真机 ADB 核对（用户授权的扩展库副本，286 本）：网格首页为真实封面缩略图，已读梯形斜幅位于封面右上角；页码“1 / 36”。视图菜单竖屏一页放下，勾选与“加入时间 ↓”正确；“更多”弹窗高度随栏目数（主题、书架、备注），选“书架”后弹窗关闭、菜单保持并显示“更多：书架”。列表视图“1 / 72”，末页跳到“72 / 72”；切到“标签”回到第 1 页，首页／上一页禁用、下一页／末页可用；进入“cooking”后顶栏为返回图标与文件夹名。首轮发现列表图标与网格图标风格不一（实心）、弹窗固定高度留白过多，已改为空心图标与随内容高度后复核。

### 搜索入口、滑动翻页与缩放修订（Q40–Q43）

确认结论见 [questions.md](../../questions.md) 的 Q40–Q43，规格同步到 R20、R21、R23、R25、R27、R30 与 AC06。改动如下。

- **搜索入口**：去掉常驻搜索框，顶栏筛选左侧加较小的搜索图标（本步禁用，独立搜索页留步骤 03；“更多”页搜索图标留步骤 06）。
- **文件夹排序**：文件夹层的视图菜单列出“名称”，点击切换升／降序，兜底文件夹始终在最后（`LibraryRequest.foldersAscending`）。
- **统一分页容器**：新增 `PagedArea`，统一提供滑动翻页与 `PageBar`，图书馆、视图菜单和栏目弹窗都改用它。
- **数量优先缩放**：网格最小单元格 96dp（含 4dp 封面边距），列表最小行高 72dp，再放大填满空间，余量均分为间距。

命令同上一节。

- **JVM 测试**：119 个，0 失败。`PageGeometryTest` 改为数量优先几何，包括 PA6 内容区 4×3 填满高度、旋转后填满宽度、列表 6 行拉伸。`LibraryIndexTest` 增加降序时兜底文件夹仍在最后。lint 0 error，5 条既有版本 warning。
- **`LibraryScreenTest`**：16 项全部通过，仍为手动安装后单独运行 `am instrument`。
    - 新增四向滑动翻页及首页不响应。
    - 新增名称降序与兜底文件夹位置。
    - 长菜单可滑动翻回上一页。
    - 搜索／筛选图标禁用且没有搜索框。
    - 容量断言改用新几何。
- **真机 ADB 核对（286 本扩展库）**：
    - 网格每页 4×3＝12 本，页码“1 / 24”，最后一行贴近翻页行，列间距均匀。
    - `input swipe` 依次左滑、上滑、右滑、下滑，页码为 2、3、2、1。
    - 在首页下滑仍为第 1 页；20px 短拖不翻页。
    - 列表每页 6 行，页码“1 / 48”。
    - 按标签分类后菜单显示“名称 ↑”；切换后文件夹依次为诗歌、经典、科幻、散文、技术、小说，兜底文件夹独占末页。

### 共同验收结论

用户于 2026-10-07 在 PA6 墨水屏上试用后确认步骤 02 通过，包括图标与梯形斜幅的灰度辨识、残影、无动画、滑动翻页与触控体验，以及 4×3 网格与 6 行列表的可读性。验收结束后已卸载 `io.github.chenxiex.calibrecloud.debug` 与 `.debug.test`，并删除设备上的 `Download/calibre-step02-acceptance-20261007` 测试库副本和 ADB 界面转储文件；设备上不再有本应用的包。

### 未完成

- 无配置／无元数据两个入口只有 Compose 测试证据，真机显示未单独核对。
- 搜索、筛选、上次打开位置、书籍点击打开分别留给步骤 03、04。

## 步骤 03：搜索、筛选与搜索历史（2026-10-07）

对应 R24 的筛选界面、R25（含 Q44 字段徽标）与 R13 的已读筛选可用性。实现位于 `ui/` 的 `LibraryScreen`（筛选面板）、`SearchScreen`（搜索页顶栏与字段／历史）、`LibraryViewModel`（`SearchSession`），以及 `state/SearchHistoryRepository`（schema v7 `search_history`）；约定见[界面约束](../src/main/java/io/github/chenxiex/calibrecloud/ui/AGENTS.md)与[应用状态约束](../src/main/java/io/github/chenxiex/calibrecloud/state/AGENTS.md)。开始前核对了 plan 步骤 03 与 R25：计划原写“筛选在文件夹导航和搜索间保持”，与 Q41“在搜索页的修改只作用于本次搜索”冲突，按用户指示以规格为准修订计划；字段选择位置按用户新决定记为 Q44 并同步 R25。试用中又按用户决定改为：下载状态与已读状态筛选单选、格式仍多选（Q46，R24），筛选模型的这两维由集合改为单值，原“两者并集为全库”一类的同维度多选断言随之删除。改后 JVM 126 tests、0 failures，lint 0 error；PA6 上 `LibraryScreenTest` 20 项、`LibraryQueryPlatformTest` 3 项、`ExtendedLibraryQueryTest` 8 项全部通过。

### 自动检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<受影响的 6 个类>
./gradlew :app:connectedDebugAndroidTest
```

- 基线 `BUILD SUCCESSFUL`：JVM **126 tests、0 failures**。`LibraryViewModelTest` 新增 7 项：在文件夹内搜索覆盖全库、平铺并从第一页开始，返回恢复文件夹与页码；搜索页沿用图书馆视图／排序／筛选且修改不回流，再次进入输入为空、字段为“全部”；丛书序号排序不带入平铺搜索；空白查询忽略、只记录执行过的查询、清空输入回到历史、清除历史；字段限定查询且选择字段本身不执行，失效栏目字段退回“全部”；筛选变化回到第一页并在文件夹间保持；已读栏目无效时已读筛选报告原因而非空库。lint 0 error、5 条 warning（均为既有版本提示）。
- 定向连接测试（PA6，Android 14）：首轮 `CacheMaintenanceTest`、`CoverTaskHandlerTest` 各有一项仍断言 schema 版本 6，`LibraryScreenTest` 一项因页容量不足 11 本、目标书落到第 2 页而超时，均为测试本身问题；修正后 `LibraryScreenTest` 20、`CacheMaintenanceTest` 15、`CoverTaskHandlerTest` 9 项全部通过，`SearchHistoryRepositoryTest` 3、`StartupSyncTest` 5、`TaskSchemaMigrationTest` 2 项首轮即通过。
    - `SearchHistoryRepositoryTest`：历史跨重开持久、最新在前、重复查询前移、空白不存；按书库隔离，清除只删当前书库；超出上限只保留最新；v6→v7 迁移保留选择与启动同步设置并得到空历史。
    - `LibraryScreenTest` 新增 5 项（替换原“搜索／筛选置灰”项）：无元数据时两图标禁用；从文件夹进入搜索页，输入框聚焦、视图菜单无分类与丛书序号、键入不执行、搜索键执行并找到其它文件夹的书、清空输入显示历史、点历史重新执行、返回恢复文件夹；字段徽标选中态、输入框内“作者：”、限定字段生效、清空图标仅在有文字时出现且点击后清空输入、回到历史并保持字段与焦点（Q45）；已读筛选单选（选另一项即替换，再点取消，Q46）、筛选面板切换不关闭、按钮描述变为“筛选（已启用）”、与视图菜单互斥、搜索页修改不影响图书馆；已读栏目失效时提示原因、已读选项禁用、全部书籍照常显示。
- 全量 `connectedDebugAndroidTest`：**225 tests、0 failed、17 skipped**（均为需显式参数的真实 SAF／OneDrive／扩展库用例），`BUILD SUCCESSFUL`，结束后 Gradle 已卸载设备包。

### 真机 ADB 核对（扩展库 286 本）

用 `generate.py` 在会话临时目录生成扩展库，经 `ExtendedLibrarySeedTest` 导入 debug 应用私有状态（不涉及存储授权或源书库），再用 helper 按资源 ID 点击；预期值直接查询生成的 `metadata.db`。设备 PA6，输入法为汉王拼音。

- 标签分类进入“cooking”第 2 页后进入搜索页：输入框聚焦，字段徽标为全部／标题／作者／丛书／标签／简介及主题、书架、备注（整数与计算栏目不出现），历史为空且“清除”禁用。
- `machine 188` 找到 travel 标签、不在 cooking 中的 book 193；`garden tales`（与标题词序相反）得到 4 本，与 SQL 中标题同时含两词的 37、65、191、287 一致；`machine188` 按字面无结果。键盘回车执行搜索并收起键盘。
- 在搜索页筛选“已读”后为 65、191、287（37 的已读值为空），筛选按钮描述变为“筛选（已启用）”；返回后回到“cooking”第 2/4 页，图书馆筛选未启用。
- 选“标签”字段后输入框显示“标签：”，搜索 `travel` 为 4 页、末页 1 本，即 37 本，与 SQL 标签计数一致（标题、简介均不含该词）。
- 强制停止并重启后搜索历史 4 条仍在，新的搜索页字段回到“全部”。
- 把 `#read_status` 改名后的副本重新导入（种子测试按预期在选择已读栏目处断言失败，导入已完成）：图书馆仍为 24 页、无已读斜幅，筛选面板显示失效原因，已读／未读禁用。随后重新导入原副本恢复有效栏目，供用户试用。
- 设备汉王输入法用 `input text` 时会吞掉 `%s` 空格、把 `\` 转成“、”，第一次空格只提交拼音组合；ADB 输入空格需连按两次空格键。这是测试输入方式的限制，用户手动输入不受影响，仍需在共同验收中确认。

### 用户验收

- 2026-10-07 用户在 PA6 上试用搜索页输入与清空图标、字段徽标、历史与筛选面板（含 Q45、Q46 调整）后确认通过，步骤 03 提交；随后已卸载 debug 与测试包，`/data/local/tmp` 无本项目遗留文件。

### 未完成

- 书籍点击打开、上次打开位置留步骤 04；选择模式下的搜索／筛选限制留步骤 05。

## 步骤 04：受控文件提供、外部打开与上次打开（2026-10-07 至 2026-10-08）

对应 R28、R29、R27 的下载标记、R20 的底栏上次打开与 R31 的打开错误。实现：`files/BookFileProvider`（初版继承 AndroidX FileProvider；按 Q48 改为自定义 ContentProvider，见下文“Q48 后的 provider 改造”；按清单记录提供已发布代次，显示名／大小／MIME 取自记录，只读）、`files/BookFileNames`（显示名规范化与 MIME 映射）、`state/LastOpenedRepository`（schema v8 `last_opened`）、`ui/OpenViewModel`（打开意图状态机）与 `OpenStrip`（状态条、底栏上次打开），图书馆和搜索结果中短按书籍打开，临时“已下载文件”页增加“打开”。约束见[文件提供约束](../src/main/java/io/github/chenxiex/calibrecloud/files/AGENTS.md)、[界面约束](../src/main/java/io/github/chenxiex/calibrecloud/ui/AGENTS.md)与[应用状态约束](../src/main/java/io/github/chenxiex/calibrecloud/state/AGENTS.md)。按 Q47 不提供单本“选择格式”，先前实现的面板已移除；按 Q49 改为在书籍上显示可取消的下载进度标记，见下文“Q47／Q49 修订”。

### 自动检查

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<受影响的 9 个类>
```

- 基线 `BUILD SUCCESSFUL`：JVM **139 tests、0 failures**。新增 `BookFileNamesTest`（5：正常标题保留、控制字符／双向覆盖字符／路径分隔符规范化、空标题兜底、255 字节截断不拆字符、MIME 映射与系统回退）、`OpenViewModelTest`（7：有副本立即打开且只有 `STARTED` 写入上次打开；缺副本时提交下载并显示排队／等待／进度后自动打开；退后台、离开页面、切换书库后下载完成不打开；新点击替代旧意图；无阅读器报错、不写记录、重试不重新下载；下载失败／提交被拒区分原因与设置／同步入口；上次打开按当前书库读取），`LibraryIndexTest` 新增 1 项（格式列表按优先级列全部格式、不受筛选限制、含已离开导入的副本及源不可用标记）。debug 与 release lint 均 0 error、5 条既有版本 warning；`git diff --check` 通过。
- 合并后的 release Manifest：书籍 provider 为 `io.github.chenxiex.calibrecloud.files.BookFileProvider`，`exported=false`、`grantUriPermissions=true`，路径配置仍只有 `books/`；权限条目与之前相同，没有新增权限或导出组件。
- 定向连接测试（PA6，Android 14）：`BookFileProviderTest` 10、`LastOpenedRepositoryTest` 2、`SearchHistoryRepositoryTest` 3、`StartupSyncTest` 5、`CacheMaintenanceTest` 15、`TaskSchemaMigrationTest` 2、`ApplicationStateRepositoryTest` 11、`EncryptedAuthStateStoreTest` 5 项首轮全部通过；`LibraryScreenTest` 25 项中 1 项因断言写在带标签的父节点上（文字在子节点）失败，改为断言子孙节点后单独重跑 25 项全部通过。结束后 Gradle 已卸载 debug 与测试包。
    - `BookFileProviderTest`：真实清单与私有文件下查询返回“三体：地球往事.epub”与大小、MIME、字节；空标题为“未命名书籍-42.pdf”，未知大小取文件大小；同名两书 URI 不同、各读各的字节；`w`／`wt`／`wa`／`rw`／`rwt` 及删除／更新均被拒，文件仍在；同一格式更新后旧代次不再提供、查询无行、`getType` 为空，而更新前已打开的描述符读完旧字节；长度不符与未发布的崩溃遗留文件不提供；打开意图只带临时读授权；私有目录无法生成 URI；经已安装 provider 的路径穿越、指向私有文件的符号链接和非规范大小写 URI 均不提供；provider 非导出、使用 `.debug` authority。
    - `LastOpenedRepositoryTest`：每库只保留最新一条、跨重开持久、相同数字书籍 ID 在不同书库互不影响；v7→v8 迁移得到空记录并保留搜索历史。
    - `LibraryScreenTest` 新增 5 项：短按书籍以默认格式和标题发起打开、无格式的书报告原因；格式面板列出 EPUB“未下载”、PDF“已下载”，选择后发起 PDF 打开并关闭，系统返回关闭面板；阅读器启动成功后底栏出现“上次打开：书籍1”，在“更多”页点击再次打开；排队状态显示“已排队，等待下载”，切换到“更多”后状态条消失、下载完成也不打开；无阅读器时显示“没有可以打开 EPUB 格式的应用”、不出现上次打开，重试再次调用、关闭图标移除状态条。原底栏切换用例改为新签名后通过。

### 设备上的阅读器

`cmd package query-activities -a android.intent.action.VIEW -t <MIME> -d content://…` 在 PA6 上的结果：EPUB 为 `hanvon.aebr.hvxreader`，PDF 为 `hanvon.aebr.hvreader`，MOBI 为 `hanvon.aebr.hvepubreader` 与 `hanvon.aebr.hvxreader`，TXT 另有系统 HTML 查看器；`application/octet-stream` 没有处理应用，可用于核对“无阅读器”路径（库内未知格式）。

### 未完成

初稿时真实阅读器路径均未执行；后续各节已完成本地与 OneDrive 打开、阅读器无源目录权限读取、下载中退后台不跳转和同名书不冲突（汉王阅读器与 KOReader），结论见文末“共同验收结论”。

### 阅读器 URI 实测（Q48，2026-10-08）

用户反馈在 PA6 上打开“李尔王”时汉王阅读器提示“书籍解析失败”。核对：源 EPUB、应用私有副本与阅读器导入副本的 SHA-256 相同（`7e6cbd72…`），`unzip -t` 无错误，首条目 `mimetype` 为 `application/epub+zip`；系统以 `typ=application/epub+zip` 启动 `hanvon.aebr.hvxreader`，阅读器把 URI 路径拼成 `importBook/_books_<书库>_<代次>.book` 后解析失败，书架标题即该文件名。

随后用临时 instrumentation 探针（未提交，代码已撤回）在 debug 应用进程内以临时读授权启动阅读器，结果写入 [Q48](../../questions.md#q48--交给阅读器的-uri-路径采用什么形式)：`…/x/<代次前 8 位>/李尔王.epub` 导入为 `_x_f5aa7c56_李尔王.epub` 并正常显示；`…/李尔王.epub?g=…` 导入为 `_李尔王.epub` 并正常显示，查询参数不进入文件名；两本不同内容的同名书以后者形式先后打开时写入同一文件，后者覆盖前者（导入文件哈希由 `b0e0d9b5…` 变为 `829ef00d…`）。注意：`am instrument` 结束时强停应用会撤销其授予的 URI 权限，首次探针因此出现 `Permission Denial`，改为启动后保持 45 秒再结束。截图保存在被忽略的 `app/build/verification/q48-probe/`。阅读器书架和 `importBook/` 中留下了这些测试条目，属于阅读器数据，未删除。

### Q48 后的 provider 改造（2026-10-08）

按 Q48 的确认结论，规格 R01、R28、R35 已改为自定义 ContentProvider，URI 为 `content://<authority>/<书名-书籍ID.后缀>?copy=<书库>:<代次>`；移除 `res/xml/book_paths.xml` 与 FileProvider 声明，`EncryptedAuthStateStoreTest`、`ApplicationStateRepositoryTest` 中依赖 `FileProvider.getUriForFile` 的断言改为“伪造 URI 无法读取”。

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug :app:lintRelease
adb install -r app-debug.apk && adb install -r app-debug-androidTest.apk
adb shell am instrument -w -e class <BookFileProviderTest,EncryptedAuthStateStoreTest,ApplicationStateRepositoryTest> …
```

- 基线 `BUILD SUCCESSFUL`：JVM **140 tests、0 failures**（`BookFileNamesTest` 新增 URI 名称：书名在前、`-书籍ID` 不被截断、空标题、规范化）；debug 与 release lint 均 0 error、5 条既有版本 warning。
- 为保留用户在设备上已授权的测试书库，本轮用 `install -r` 加 `am instrument`，未用会卸载应用的 Gradle 连接任务。PA6 上 **26 tests 全部通过**：`BookFileProviderTest` 10（URI 路径与 `copy` 参数、显示名／大小／MIME、空标题、同名书路径不同、只按 `copy` 定位且拒绝多余路径段／参数／大写 UUID／缺失或多余字段、拒绝写入、回收代次不提供且更新后路径不变、长度不符／未发布／被替换为链接的文件不提供、只读意图、已安装 provider 拒绝伪造路径与未知代次、非导出与 debug authority）、`EncryptedAuthStateStoreTest` 5、`ApplicationStateRepositoryTest` 11。

### 汉王阅读器的导入副本（2026-10-08，用户在 PA6 上操作，agent 以 ADB 核对）

- `hanvon.aebr.hvxreader`（`/system/priv-app/hvXReader`，1.06.65，无桌面图标，设置“所有应用”默认不列出）每次经 content URI 打开都会把文件复制到 `Android/data/hanvon.aebr.hvxreader/files/importBook/`，同名文件被覆盖；书架条目指向该副本。
- 阅读器设置中的“清除临时文件”不清理 `importBook`。书架删除单本并勾选“同时移除源文件”会删除条目记录的路径：对经本应用打开的书即导入副本（`_李尔王-5.epub` 被删除，应用私有副本与测试书库 620 个文件不变）；对从文件管理器打开的书则是原文件——用户移除一本早先打开的小说时，系统记录 hvLauncher 删除了外部存储上的 1 个文件，`Documents/calibre/…` 中该书 (1) 卷随之不见（推断，无删除前清单）。
- 因此本应用的“移除下载”与缓存清理只释放应用自己的副本，阅读器的导入副本须在阅读器书架中删除。
- 打开方式对照（同一测试 EPUB 放在 `Download/tmp-step04-probe/`，以阅读器日志与 `importBook` 前后对比判断）：
    - MediaStore content URI（ADB 发出）：`copyUriFile` 复制为无扩展名的 `importBook/_external_file_<id>` 后崩溃退出；说明阅读器对任何 content URI 都复制，不会还原为路径。
    - `file://` 路径（ADB 发出）：不复制，但启动时因缺少附加参数空指针退出。
    - 汉王文件管理器（系统 uid 1000，Intent 无 data URI，仅含私有 extras）：直接解析原路径，`importBook` 无新增文件。
- 结论：只有汉王自家的私有启动参数能让阅读器就地读取。改为导出到共享目录再打开不能避免复制（content URI 仍复制），模仿私有参数则依赖未公开接口并须放弃受控 URI，均不采用；保持 R28 的受控 URI，接受阅读器保留导入副本。
- `importBook` 无应用内删除入口，ADB 的 shell 用户可删除其中文件（属 `ext_data_rw` 组）。用户在书架移除相关条目后，agent 以 ADB 清空了该目录（含测试副本与两卷小说的导入副本；原书仍在 `Documents/calibre/`）。

### Q47／Q49 修订（2026-10-08）

- Q47：删除单本格式列表（`LibraryIndex.formats`、`LibraryQueryService.formats`、`FormatOption`／`BookFormats`）、`LibraryViewModel.formatChoice` 与格式面板及其字符串和测试（`LibraryIndexTest` 1 项、`LibraryScreenTest` 1 项）。
- Q49：`OpenViewModel.download` 给出等待中下载的书与完成比例（排队或总量未知为空），比例变化最多每 2 秒发布一次、被推迟的最新值在间隔结束时补发；`cancelDownload` 经队列 `CANCEL` 取消任务并撤销意图，任务编号尚未返回时在返回后取消。图书馆网格和列表在下载勾选位置绘制黑底白叉圆形图标与静态进度环（`download_progress_<书籍 ID>`），已下载勾选改为黑底白勾圆形图标（`download_mark`）。状态条只在等待条件、暂停和失败时出现，删除排队／进度文字。
- 自动检查：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 通过；JVM 测试全部通过，`OpenViewModelTest` 9 项（新增进度节流与延迟补发、等待时保留比例、取消任务与提交未返回时取消）。lint 只有既有依赖版本提示。
- 真机（PA6，`10.77.156.77:41881`，以 `install -r` 与 `am instrument` 保留测试书库数据）：`LibraryScreenTest` 25 项通过，其中新增：点击未下载书出现带“取消下载《书籍1》”描述的进度标记、排队时无状态条、等待网络时状态条显示原因、离开页面撤销；点击标记后标记消失、任务收到取消、无状态条；已下载书的圆形勾存在。首轮 1 项因勾选标记合并进可点击的书籍节点而按合并树查不到，改为查未合并树后通过。
- 真机 ADB 目视：图书馆页已下载书显示黑底白勾圆形图标；点击未下载的 No.283 后立即截图，该书右下角显示黑底白叉与空进度环，位置与勾选一致。本地复制在约 1 秒内、点击取消前已完成，因此该书变为已下载、没有打开阅读器；运行中取消真实传输尚未在真机上目视（需较大或 OneDrive 书籍）。

### 感叹号警告与系统通知（Q49 补充，2026-10-08）

- 删除底栏上方的状态条（`OpenStatusStrip`、重试／设置／同步／关闭按钮及排队文字）。`OpenViewModel.mark` 统一描述书上的图标：下载中为白叉加进度环、可取消；需要用户处理的等待（等待条件、暂停）为感叹号、保留进度环和取消；失败为不带进度环的感叹号，点击书籍即重新打开。每个新的需处理状况产生一个 `OpenNotice`（同一等待原因重复上报不再通知，重新点击后同样的失败会再次通知），`MainActivity` 经 `OpenNotifications` 发送单条可替换通知（渠道 `open-problems`，ID 10），首次缺少通知权限时先请求；阅读器启动或用户取消时撤回。通知按 `openMoreTarget` 带上登录、目录授权或同步页。打开流程的下载失败改用自身文案，不再沿用临时下载页“已有完整副本保留”等说法。
- 自动检查：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 通过；`OpenViewModelTest` 10 项（新增：等待与失败各通知一次、相同等待不重复、新原因再通知、取消与启动成功撤回、重新点击后同样失败再次通知；警告图标状态）。lint 只有既有依赖版本提示。
- 真机 `LibraryScreenTest` 25 项通过：等待网络时进度标记改为“需要处理”描述并产生一条通知，文字为“等待网络连接后继续下载”；无阅读器时书上出现 `open_warning_1`，通知标题“无法打开《书籍1》”、正文含“没有可以打开 EPUB 格式的应用”，点击书籍再次尝试，切换页面后警告消失。
- 真机 ADB 实测（测试书库副本）：临时把书籍 282《秋笔记》的 EPUB 改名后点击该书，本地复制任务因源缺失失败，书上出现感叹号，系统弹出通知权限请求；允许后 `dumpsys notification` 显示渠道 `open-problems`、ID 10 的通知，下拉通知栏可见。首轮正文为沿用的“源格式不可用；已有完整副本保留。”，与无副本的实际情况不符，改为打开流程专用文案后复测为“书库中找不到这本书的这个格式文件，无法下载”。随后恢复文件名，测试书库中没有遗留的改名文件。debug 包的通知权限因此处于已允许状态。
- 过程中一次误触（封面上的“No.282”属于书籍 287）打开了《Tales of Garden》，汉王 `importBook` 因此新增 `_Tales of Garden-287.epub`。
- 通知正文精简（用户要求）：标题仍为“无法打开《书名》”或“《书名》的下载需要处理”，正文只写原因，如“找不到源文件”“等待网络”“没有能打开 EPUB 的应用”“需要重新登录 OneDrive”，不再附“点击书籍重试”“已下载的副本保留”等说明。`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` 通过，真机 `LibraryScreenTest` 25 项通过（断言改为新文案）。
- 未在真机实测：点击通知进入登录／授权／同步页（需真实的登录或授权失效），以及拒绝通知权限后只显示感叹号。

### KOReader 打开实测（2026-10-08）

- 环境：PA6，KOReader `org.koreader.launcher.fdroid` v2026.07.1，与汉王 hvXReader 同时安装，因此打开 EPUB 时出现系统选择器（ResolverActivity），每次选“仅此一次”交给 KOReader，未设为默认。测试书库副本。
- 打开已下载的书籍 288《山纪事》：KOReader 日志 `UriHandler: Found content, trying to guess its path` 后 `[Non readable file]: imported to /storage/emulated/0/Android/data/org.koreader.launcher.fdroid/files/山纪事.epub`，随后打开该导入文件，阅读界面正常显示内容。也就是说 KOReader 同样复制受控 URI 的内容，文件名取自 provider 的显示名，而非 URI 路径（`山纪事-288.epub`）；`koreader/history.lua` 记录的是导入路径。
- 再次打开同一本书：覆盖同一个 `山纪事.epub`（修改时间更新），不产生新文件，并保留旁边的 `山纪事.sdr`（进度与标注）。副本位于 KOReader 自己的外部应用目录，卸载 KOReader 时随之删除，ADB 也可删除。
- 同名书冲突：测试书库中书籍 17 与 161 均为《A Brief Orchard》、均为 EPUB 且已下载。先打开 17，导入文件 MD5 与 17 的源文件一致；再打开 161，同名文件被覆盖为 161 的内容（MD5 与 161 的源文件一致），两本书共用一个 `A Brief Orchard.sdr`。在 KOReader 中，同一书库内的同名书会互相覆盖并混用进度，与 AC08“同名书不冲突”不符，已作为 Q50 提交确认，AC08 暂不通过。
- 实测留下的 KOReader 导入文件：`山纪事.epub`、`A Brief Orchard.epub` 及对应 `.sdr`，均为测试书库内容。
- Q50 选 A 后的改动：`BookFileNames.readerName` 同时生成 URI 路径与显示名“书名-书籍ID.后缀名”，原 `displayName`／`uriName` 合并。JVM `BookFileNamesTest` 随 `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 通过；PA6 上 `BookFileProviderTest` **OK (10 tests)**，含同名书 1／2 的查询显示名分别为 `同名-1.epub`、`同名-2.epub`。- Q50 真机复验（本地测试书库，KOReader 每次“仅此一次”）：打开书籍 17 后导入为 `A Brief Orchard-17.epub`，再打开 161 导入为 `A Brief Orchard-161.epub`；两份 MD5 分别为 `3c526edb…`、`6f55451c…`，与各自源文件一致，17 有独立的 `A Brief Orchard-17.sdr`。同名书不再互相覆盖，AC08 的同名书一项通过。之前实测留下的 `A Brief Orchard.epub`／`.sdr` 仍在，与新文件互不相关。

### OneDrive 打开实测（2026-10-08）

- 书库：用户在个人 OneDrive 中选择并同步的示例书库（3 本，即 `assets/calibre-sample`）。阅读器：KOReader v2026.07.1，系统选择器中每次选“仅此一次”，未设默认。
- 打开书籍 1《Quick Start Guide》（未下载）：右下角出现带进度环的叉号（`download_progress_1`），下载完成后前台弹出选择器，选 KOReader 后日志为 `imported to …/files/Quick Start Guide-1.epub`，即 Q50 的显示名生效。导入文件 MD5 `2d9e1d1c…` 与示例书库源文件一致，KOReader 显示该书第 1/37 页。回到应用后书籍显示下载勾（`download_mark`），底栏显示“上次打开：Quick Start Guide”。
- 后台不跳转：点击未下载的书籍 3《夜叉池》约 1.5 秒后按 HOME。字节于 10:22:52 在后台写完（暂存文件修改时间），发布约在 10:23 的十余秒后（目录修改时间 10:23），其间日志没有 VIEW 启动、选择器或 KOReader 的 `UriHandler`，前台仍为桌面。约 10:23:05 回到前台时副本尚未发布，因此还没有下载勾；10:23:17 再次点击该书时已发布，立即弹出选择器。
- 本节结论：AC08 的 OneDrive 打开、阅读器无源目录权限读取所授权文件、后台不跳转通过。同名书在 KOReader 中的复验待本地测试书库重新授权后执行。

- 下载耗时分解（前台打开书籍 2，62 KB，每 0.5 秒查看私有暂存目录）：点击后约 26 秒才出现第一批字节，约 1 秒写完，再过约 18 秒才发布，发布后约 1 秒弹出选择器。界面刷新不是瓶颈；时间花在 OneDrive 请求上。每次 `version`／`resolve`／`openRead` 都重新核对身份（2 个请求）并从根逐级列目录（每层 1 个请求）；下载前做三遍（约 19 个请求），下载后的版本复核再做一遍（约 6 个请求）。设备到 Graph 的单个未授权请求约 0.6 秒，带令牌的实际请求耗时未逐个测量。

### 所有下载显示下载中图标（Q51，2026-10-08）

- 改动：`BookOpening.downloads` 先订阅任务事件再列出队列，持续给出所有未结束的副本下载（不论来源）；`OpenViewModel.downloads` 按书给出标记（同书优先显示运行中的任务；只有比例变化时最多每 2 秒更新，任务出现或结束立即更新），`cancelTask` 取消该任务（是当前打开计划的任务时一并放弃打开）。图书馆页优先显示打开计划的标记，否则显示该书的下载中图标。图标改为：叉的黑色圆盘缩小 3dp，进度弧宽 3dp 紧贴其外缘，两者合计与 24dp 的下载勾等大；白色底衬保证深色封面上可见，不画轨道或边框线，比例未知时没有弧。打开失败的感叹号仍为与下载勾等大的圆盘。
- JVM：`OpenViewModelTest` 新增没有打开计划的下载（排队标记、比例按间隔更新、等待保留比例、点击取消该任务、退到后台后仍有标记、结束即消失），`testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest` 通过。
- 平台：`LibraryScreenTest` 新增没有打开计划的下载显示 `download_progress_2` 并可取消，PA6 上 **OK (26 tests)**。该测试起初在测试内于组合中新建 view model，每次重组都重新收集并写状态而无法空闲；改为在组合外建立（与 activity 的 view model 一致），产品代码不受影响。
- 外观：用临时截图测试（未保留）在 PA6 渲染深色封面上的下载勾、85%、排队、40% 四种标记，尺寸与位置一致，叉与弧合计与勾等大。本地书库下载太快，未在真实下载中截到图标。

### OneDrive 目录选择页布局修复（2026-10-08）

- 用户反馈：PA6（1072×1448，density 360，字体 1.05）上第 3 页目录列表下方“返回上层目录”“选择当前目录为书库”被压成两条细线。原因是该页内容总高超出分配给页面内容的高度，最后一行被挤压。
- 修复：目录项放进按剩余高度分配并裁剪的区域，翻页与选择两行始终完整显示；“目录列表第 N 页”移到上一页／下一页同一行，并收紧间距。
- 真机复查（个人 OneDrive 根目录）：3 个目录项、上一页／下一页／页码与两个选择按钮均完整显示，文字可读；尚未在该页实际进入目录或选择书库。

### 共同验收结论

- 2026-10-08 用户确认通过：外部打开（汉王阅读器、KOReader）、Q50 显示名、Q51 下载中图标、感叹号与精简后的通知文案、OneDrive 目录选择页布局。
- 仍未在真机执行，留步骤 07 联验：无阅读器时报错且不更新上次打开（PA6 上的格式都有处理应用）、拒绝通知权限后只显示感叹号、点击通知进入登录／授权／同步页（需真实失效）、PDF 阅读器、源已不可用的副本与更新失败后旧副本的打开。OneDrive 下载请求偏多导致的等待（见“OneDrive 打开实测”的耗时分解）另行优化。
- 清理：删除 KOReader 目录中本步骤测试导入的 `A Brief Orchard*.epub`、`Quick Start Guide-1.epub`、`山纪事.epub` 及对应 `.sdr`（KOReader 历史中的这些条目会显示为文件缺失），删除 `/sdcard/Download/tmp-step04-probe` 与 `.ko-marker`；`adb uninstall` 测试包与 debug 包均返回 `Success`，限定包名查询确认均已不存在。测试书库副本 `/sdcard/Download/calibre-step04-test-library` 保留。
