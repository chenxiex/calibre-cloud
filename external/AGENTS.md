# 外部阅读器适配

本目录存放第三方阅读器调用 [R37 外部控制接口](../spec.md#r37--外部阅读器控制接口)的适配代码，每种阅读器一个子目录（Q90），例如 [koreader/](koreader/AGENTS.md)。遵循根 [AGENTS.md](../AGENTS.md)。

- 适配只能经 R37 定义的请求格式与入口调用本应用，格式见[外部控制约束](../app/src/main/java/io/github/chenxiex/calibrecloud/control/AGENTS.md)；不读写本应用私有数据，不访问书库源文件，也不按阅读进度推断已读（R29）。
- 适配与应用使用同一版本号，由 [release.yml](../.github/workflows/release.yml) 打包为 Release 附件；新增适配时在该工作流中加入其版本核对与打包步骤。
- 令牌由适配生成并保存在阅读器自己的设置中，不得写入日志或提示文字。
- 验收在目标设备上以真实阅读器和测试书库副本进行，结果写入[外部控制验证记录](../app/verification/external-control.md)；测试只安装 debug 包，结束后移除测试插件。
