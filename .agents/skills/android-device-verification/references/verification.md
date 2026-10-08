# 工具验证记录

执行日期：2026-10-06（UTC）。这是用户要求的仓库验收工具优化，不新增产品需求，不推进 `plan.md` 的步骤 08。

## 初版已执行（护栏强化前）

- `python3 -m unittest discover -s .agents/skills/android-device-verification/scripts -p 'test_*.py' -v`：23 项离线测试通过。覆盖匹配歧义／禁用／无效边界拒绝点击、可点击父节点、明确索引、页面变化轮询、后置条件超时停止后续步骤、全流程预验证、设备锁、ADB 参数转义与临时文件清理、产物路径／符号链接边界、标签按需输出、坐标设备条件及字体缩放变化拒绝点击、CLI 成功／失败产物路径。
- 独立 CLI 探测使用 `/tmp` 中的假 `adb` 可执行文件：`inspect` 默认不输出标签；“目录页 → 确认页 → 完成页”的三步流程完成，两次点击后都观察到预期页面；XML 产物已保存。没有调用真实 ADB 或设备。临时探测文件随测试删除。
- skill-creator 的 `quick_validate.py`：技能校验通过。当前 Python 缺少 PyYAML 和 venv 的 ensurepip；仅将 PyYAML 源码加载到 `/tmp/calibre-cloud-skill-validation/deps` 用于该校验，无全局安装或系统目录改动。helper 本身只使用标准库。
- Python `tomllib`：仓库角色配置可解析，必需字段及 `gpt-6-luna`／`low` 设置有效；配置格式按 [Codex 官方自定义 agent 文档](https://learn.chatgpt.com/docs/agent-configuration/subagents) 核对。本地 CLI 版本为 `0.160.1`。
- 文档独立审阅：仓库导航、角色与技能引用、授权和验收证据边界一致。静态链接检查和 `git diff --check` 通过。

## 生产设备护栏强化

用户明确要求谨慎对待生产设备误触，随后强化角色约束与 helper：轻量角色只执行主 agent 已核对的元素流程，禁止改写条件、坐标、裸 ADB 输入、安装／清除／设置／删除等操作；未知页面或点击结果未知时停止并交还设备执行权。

- 先添加点击前置条件／焦点／稳定性回归，旧 helper 的 6 项新增检查失败，再实施修复。独立审阅随后发现后置校验未核对焦点的问题；新增 3 项回归稳定复现误报成功或继续后续步骤，再修复后置等待。
- 最终重新执行上述 unittest 命令：42 项离线测试通过。新增覆盖包限定及独立前置条件、禁止索引、全流程预检、焦点未知／切换／覆盖拒绝、两次目标稳定性、叶子与父节点交集点击、坐标设备／页面变化、`--elements-only` 提前拒绝坐标、点击传输或后置失败的可能已执行标记、停止后续步骤、允许已知前后包过渡但不接受不一致样本。
- 产物隐私检查：默认不保存完整 XML，仅保存短结果 JSON；原始 XML 需显式 `--save-xml`。轻量角色不能启用该选项或自主截图。
- Python 编译检查、技能校验、角色 TOML 解析、文档链接及 `git diff --check` 通过。仅变更验收工具与约束，不执行 Android 产品回归。

初版的无前置条件单次点击调用已不再支持；原始 XML 默认保存行为也已取消。后续复用以当前操作参考及 helper CLI 为准。焦点解析采用严格模式，厂商输出不兼容时拒绝点击，等待主 agent 定向核对。

## 尚未执行

没有连接设备、安装测试包、再次授权测试目录或重跑步骤 07；步骤 07 已完成且设备数据已清理。本次没有验证真实设备 XML 兼容性、坐标配置或客户端重新加载后的角色发现／实际模型调用。这些在下一轮已授权真机验收中定向确认，不能将本次离线结果记录为真机通过。

焦点／XML 查询与 `input tap` 不能构成系统原子操作，仍有竞态；提示词与 helper 不构成完整 shell 权限下的系统隔离。不得将这些护栏解释为保证消除误触。

未改动生产应用、依赖或全局 Codex 设置，因此未重跑 Android 构建／产品回归。没有预置设备地址、账号信息或未经验证的坐标。

## 2026-10-07：PA6 焦点查询兼容

步骤 08 的实际 PA6／API 34 验收发现 `dumpsys window windows` 只输出窗口列表，没有 `mCurrentFocus`；完整 `dumpsys window` 输出包含唯一有效焦点。先添加稳定复现该差异的离线测试（修复前失败），再改用完整查询；仍拒绝缺失、空值和重复焦点，不放宽包名或页面校验。离线回归 `python3 -m unittest discover -s .agents/skills/android-device-verification/scripts -p 'test_*.py' -v` 为 **44 项通过**。随后真实 helper 导航到步骤 08 专用 `library-a`，核对接收方与目录后授权并同步成功。详细实际产物、临时 ADB 超时及步骤验收范围见[第二阶段记录](../../../../app/verification/phase-2.md)。


## 2026-10-07：PA6 设备旋转 profile 兼容

步骤 08 的 PA6／API 34 `dumpsys input` 未提供 `SurfaceOrientation`，原 helper 返回 `rotation_unavailable` 并停止建立设备 profile。先增加离线回归，`app/build/verification/step08/helper-profile-before.log` 为 50 项中 2 failures、5 errors；修复后同一 unittest 命令 **50 项全部通过**（`helper-profile-after.log`）。

有唯一合法 `SurfaceOrientation` 时沿用原路径，缺失时才检查 `dumpsys window displays` 默认 display 0 内独立数值 `mRotation`。默认 display 与数值旋转必须唯一，值限于 0–3；缺失、非法值、歧义、其它屏旋转以及内联配置中的旋转均拒绝，不能用近似文本推断设备姿态。回归覆盖四种旋转、原路径优先及上述拒绝分支，保持坐标 profile 的尺寸、字体与导航模式校验。

随后 PA6 实际 `device-profile` 成功。无线 ADB 端口变更后，旧 serial 点击在执行前失败，主 agent 重新核对新 serial fingerprint 与已记录 profile 完全一致才继续。系统选择器出现 dump 查询超时时，仍保留设备锁、双快照、包／页面与元素唯一性守卫；仅在本轮忽略执行脚本延长单次查询超时到 30 秒，没有放宽可点击范围。真实设备产物与具体可能已执行标记见[第二阶段记录](../../../../app/verification/phase-2.md)，这些结果不代表系统原子点击保证或应用墨水屏体验通过。

## 2026-10-07：查询超时配置、诊断与有限恢复

针对步骤 09 的重复 `adb_timeout` 更新 helper，未改 Android 应用行为或扩展设备操作范围。新增全局 `--query-timeout`（默认 30 秒）和 `--query-retries`（默认 1，最多 2），取消此前复制脚本修改查询默认值的需要。点击前超时恢复重新执行完整双快照／焦点检查；点击后的恢复仅重查后置条件，不重发输入。连接失败、页面变化及歧义仍立即停止。

ADB 短诊断记录命令类别、阶段、耗时、预算及状态，保留最近 20 条和最后一次失败，不记录参数、路径、屏幕数据或凭据。XML 清理失败单独记录，不再覆盖原始错误或有效查询结果。后置查询共用 deadline，超时恢复不重置预算；清理有单独至多 5 秒预算，实际耗时可超出查询等待时限。参数与恢复细节见[操作参考](usage.md#超时与恢复)。

实际执行：

- 先添加 11 项超时相关测试，修复前执行失败，复现缺失参数／诊断、查询未恢复及清理错误覆盖问题；输入超时不重发的原有边界测试通过。随后修复并补充预算递减、预算耗尽诊断、连接失败不重试、坐标恢复及 CLI 短结果持久化检查。
- `python3 -m unittest discover -s .agents/skills/android-device-verification/scripts -p 'test_*.py' -q`：最终 **66 项通过**（原有 50 项及新增 16 项）。独立只读审查在中间版本执行 61 项通过，指出预算／坐标覆盖建议，最终新增相应验证。所有测试仅使用 fake／mock，不调用真实 ADB。
- skill-creator `quick_validate.py`：**Skill is valid!**。系统 Python 初次缺少 PyYAML；在被忽略的 `app/build/verification/ui-helper/validation-deps/` 下载并验证 PyYAML 6.0.3 wheel 的 SHA-256，以临时 `sys.path` 运行校验器，未安装到用户或系统目录，helper 本身仍无第三方依赖。
- helper `--help`：新参数可见；`git diff --check` 通过。

本次没有连接设备、安装 debug 包、采集页面或执行真实点击，因此不宣称真机超时已消除。新版 helper 的实际延迟与恢复效果待下一轮已授权真机验收采集；步骤 09 的既有实机结果不作为新版 helper 的通过证据。已恢复查询问题汇总记录，需要处理连接／未知动作才单独报告。

## 2026-10-08：长按

第三阶段步骤 06 的选择模式需要长按，此前只能在会话临时脚本里用裸 `input swipe` 执行。现为 helper 增加 `long-press` 子命令和 flow 的 `long_press` 步骤：参数与 `tap` 相同，另有 `--hold-ms`／`hold_ms`（默认 1000，500–5000 毫秒）；沿目标向上寻找 `long-clickable` 节点，其余前后置条件、双快照、焦点与遮挡检查与点击共用，输入以一次原地 `input swipe` 发送且不重发。遮挡判断同时把其它可长按节点视为覆盖。

- `python3 -m unittest discover -s .agents/skills/android-device-verification/scripts -p 'test_*.py'`：**69 项通过**（新增 3 项，见 `test_safety.py`）。
- PA6／API 34 实机：`flow --elements-only` 中长按书籍并确认选择计数出现、再点击“完成”返回，2 步全部确认；单次 `long-press` 命令选中搜索结果中的书籍，后置条件确认。详见[第三阶段记录](../../../../app/verification/phase-3.md)。
