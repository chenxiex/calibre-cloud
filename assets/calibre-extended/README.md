# 扩展测试书库生成脚本

由 [generate.py](generate.py) 基于 [calibre-sample](../calibre-sample/) 的副本，使用开发容器内的 Calibre 9.14.0 自带 Python API 生成 286 本书的测试书库，用于第三阶段图书馆浏览、搜索、筛选、批量操作与外部打开的验收。仓库不保存生成结果，也不修改 `calibre-sample`。

```bash
calibre-debug assets/calibre-extended/generate.py -- /tmp/calibre-extended-library
```

固定随机种子和书籍 UUID，重复生成的 `metadata.db` 数据（书籍、格式、标签、栏目值、简介、UUID）及全部书籍／封面文件逐字节一致；`last_modified` 等 Calibre 自维护的时间字段与 `metadata.db` 文件本身不保证一致。

## 内容

- 283 本新增书加样本原有 3 本。约 15% 无标签，其余 1–3 个标签；4 套丛书共 45 本，序号含小数；约三分之一无评分；46 个标题重复；约 6% 的书缺少作者关联；约 10% 无封面；约 5% 无任何格式。
- 格式：EPUB、PDF 为可打开的最小文件（含 EPUB+PDF、仅 PDF）；MOBI 仅为占位字节，不能用于阅读器验收。
- 栏目：`#read_status`（样本原有，布尔，是／否／空各约三分之一）、`#topic`（多值文本）、`#shelf`（枚举）、`#note`（文本）、`#pages`（整数）与 `#formula`（计算），后两者为不支持的类型；`#retired`（第二个布尔栏目，仅部分书有值），用于在桌面 Calibre 中改名、删除或改类型来验证栏目失效。

## 限制

- Calibre 把新书的布尔栏目初始化为“否”，也不允许丢失丛书序号或格式大小（`series_index`、`uncompressed_size` 为 NOT NULL）。因此脚本在生成后用 SQL 删除部分布尔值行和作者关联行，得到“空值”和“作者缺失”；“丛书序号缺失”“格式大小未知”无法在真实库中出现，只能由合成测试覆盖。
- 生成结果由 Calibre 写入，不等于经桌面程序打开确认；使用前请在桌面 Calibre 中打开一次。
