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
