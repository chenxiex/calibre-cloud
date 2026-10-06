# Helper 与流程

从仓库根运行，需要 Python 3 和 PATH 中的 `adb`，无 Python 第三方依赖。始终显式传入 `--serial`；无线调试地址属于本轮参数，不写入仓库配置。

```bash
adb devices -l
python3 .agents/skills/android-device-verification/scripts/android_ui.py --help
python3 .agents/skills/android-device-verification/scripts/android_ui.py --serial SERIAL inspect --selector '{"package":"io.github.chenxiex.calibrecloud.debug","text":"选择／重新授权目录"}'
```

## 定位与等待

选择器使用 `resource_id`、`text`、`content_desc`、`package` 的精确匹配，可组合约束。只读 `inspect`／`wait` 至少提供一项，可带从 0 计数的 `index`。点击目标、页面前置与后置条件都必须提供明确包名及非空的资源 ID／文字／描述，不能用 `index`；页面前置条件不能与点击目标选择器相同。目标有同名匹配时增加页面和资源 ID 约束，不能靠位置顺序消除歧义。

helper 自动找可点击父节点，并选择原目标与该父节点交集中的位置，避免直接点击大型父容器中心。目标或父节点禁用、边界无效、包名不符或点击位置被其它可点击／异包节点覆盖时拒绝点击。点击前两次查询焦点与 XML，页面条件和目标位置必须保持一致；焦点无法解析时拒绝操作。后置等待也核对焦点，仅允许点击前包与预期后置包之间过渡；未知第三包立即停止，只有前后焦点均为后置包且页面条件命中才确认成功。

`inspect` 默认返回匹配数、资源 ID、包名和有限属性，不返回页面文字。需要发现新页面按钮时，按当前包名限定查询，并显式加 `--include-labels --limit 10`；这会返回匹配元素的文字和描述，不用于登录或凭据页面。`wait` 等待唯一匹配且可用的元素，超时返回非零。`tap` 必须同时传 `--precondition` 与 `--postcondition`；轻量角色只执行主 agent 已核对的 `flow`，单次 `tap` 留给主 agent 定向处理。

```bash
python3 .agents/skills/android-device-verification/scripts/android_ui.py --serial SERIAL wait --selector '{"package":"io.github.chenxiex.calibrecloud.debug","text":"目录已授权（读写）。导入状态见“当前书库与导入”页。"}' --timeout 15
python3 .agents/skills/android-device-verification/scripts/android_ui.py --serial SERIAL --artifacts app/build/verification/ui-helper/run-01 flow --elements-only --file app/build/verification/ui-helper/saf-flow.json
```

`flow` 文件采用以下结构，连续步骤在同一设备锁内执行，失败就停止，不执行后续点击。`tap` 必须提供 `precondition` 与 `postcondition`；`wait` 不点击。`timeout` 是秒，不表示固定休眠。主 agent 必须预先核对流程和允许包名，子 agent 不得改写该文件。轻量角色必须使用 `--elements-only`，含任何坐标步骤的整个流程都会在第一个动作前被拒绝。

失败 JSON 的 `action_may_have_executed` 表示是否已有点击发出而结果未确认；为 `true` 时不能重试，应核对现状并交回主 agent。`completed_steps` 只计已确认的流程步骤，不能据此断言失败步骤没有执行。指定 `--artifacts` 时默认保存短结果 JSON，不保存完整 XML；只有主 agent 核对非敏感页面后才可显式启用 `--save-xml`。轻量角色不能使用该选项。

```json
{
    "steps": [
        {
            "action": "wait",
            "selector": {"package": "ACTUAL_PICKER_PACKAGE", "text": "EXACT_TEST_FOLDER_TITLE"},
            "timeout": 15
        },
        {
            "action": "tap",
            "precondition": {"package": "ACTUAL_PICKER_PACKAGE", "text": "EXACT_TEST_FOLDER_TITLE"},
            "selector": {"package": "ACTUAL_PICKER_PACKAGE", "resource_id": "ACTUAL_USE_FOLDER_RESOURCE_ID"},
            "postcondition": {"package": "ACTUAL_DIALOG_PACKAGE", "resource_id": "ACTUAL_ALLOW_RESOURCE_ID", "text": "ACTUAL_ALLOW_TEXT"},
            "timeout": 15
        },
        {
            "action": "tap",
            "precondition": {"package": "ACTUAL_DIALOG_PACKAGE", "text": "EXACT_CONFIRMATION_PROMPT_FOR_DEBUG_APP_AND_TEST_FOLDER"},
            "selector": {"package": "ACTUAL_DIALOG_PACKAGE", "resource_id": "ACTUAL_ALLOW_RESOURCE_ID", "text": "ACTUAL_ALLOW_TEXT"},
            "postcondition": {"package": "io.github.chenxiex.calibrecloud.debug", "text": "目录已授权（读写）。导入状态见“当前书库与导入”页。"},
            "timeout": 15
        }
    ]
}
```

这是“已导航到测试目录后的 SAF 确认”模板，所有 `ACTUAL_*` 和 `EXACT_*` 必须换成本轮元素查询取得的值，不能直接执行。不要假设厂商选择器的包名、语言或 ID 一样。运行前核对专用测试副本的提供方、完整目录位置和当前 debug 应用，确认弹窗也需核对接收方和范围；仅匹配末级目录名或“允许”不足以证明选择范围。页面未提供足够范围信息时交回主 agent，不让轻量角色猜测。确认返回应用后，再用现有授权测试／状态查询确认真实持久授权和所选位置。这段流程不负责登录、猜测目录或准备测试数据。

## 坐标与截图

优先复用选择器。需要设备坐标备用时，先用 `device-profile` 查询 build fingerprint、尺寸、密度、旋转、字体缩放和导航模式，保存原样返回值。再在 `flow` 中使用以下步骤结构；`device` 不能使用示例值，`point` 是经过实测的屏幕像素坐标：

```json
{
    "action": "coordinate",
    "profile": {
        "device": {
            "build_fingerprint": "EXACT_OBSERVED_FINGERPRINT",
            "wm_size": "EXACT_WM_SIZE_OUTPUT",
            "wm_density": "EXACT_WM_DENSITY_OUTPUT",
            "rotation": 0,
            "font_scale": "EXACT_FONT_SCALE_OUTPUT",
            "navigation_mode": "EXACT_NAVIGATION_MODE_OUTPUT"
        },
        "precondition": {"package": "ACTUAL_PACKAGE", "text": "EXACT_PAGE_MARKER"},
        "point": [100, 200]
    },
    "postcondition": {"package": "EXPECTED_PACKAGE", "text": "EXACT_NEXT_PAGE_MARKER"},
    "timeout": 15
}
```

此路径仅由主 agent 执行，轻量角色不得执行坐标动作。设备配置必须精确匹配、坐标必须在屏幕内且页面前置条件成立，才会点击；点击后仍检查 `postcondition`。helper 不自动保存坐标，也不在元素定位失败后自动尝试坐标。旋转或焦点查询不可用时会拒绝此路径。坐标记录保存在本轮忽略产物中，确认无机器专有地址或敏感内容后才考虑共享。当前没有随技能预置已验证坐标；步骤 07 的设备已清理，不编造历史证据。

不要每步截图；有限元素摘要足以定位的问题先分析摘要。只在元素不可读取或视觉验收需要时，由主 agent 核对页面范围后显式运行 `adb -s SERIAL exec-out screencap -p`，保存到本轮产物目录，再交给轻量角色定向分析。不要采集登录或凭据页面。

## 子 agent 与复验

仓库角色定义位于 `.codex/agents/android-device.toml`，不改变其它子 agent 的默认模型或全局 Codex 配置。客户端支持仓库自定义角色时选 `android_device`；新配置未被发现时重载客户端，或按技能显式传模型、推理强度和本文件路径启动普通子 agent。任务示例：

> 你独占设备 SERIAL 的操作权。使用 android-device-verification 技能，仅执行主 agent 已核对的 FLOW；本次 debug 包为 DEBUG_PACKAGE，允许系统页面包为 SYSTEM_PACKAGES，源范围为完整 TEST_LOCATION，产物写入 OUTPUT。不得修改流程或使用坐标／裸 ADB 输入。缺少范围或出现未知页面立即停止；结果未知的点击不重试。返回已完成步骤、最后确认页面、可能已执行但未确认的动作、证据路径，并交还设备执行权。

主 agent 等设备任务结束并交还执行权后才继续设备操作。脚本成功代表其声明的页面后置条件已成立，不能直接等同于所有功能验收通过。检测与点击之间仍可能发生界面变化；这些检查减少误触机会，不构成系统级隔离。角色提示词不能阻止有完整 shell 工具的 agent 绕过 helper，因此严格执行时应由主 agent 掌握流程文件和设备操作入口。

离线回归不需要设备：

```bash
python3 -m unittest discover -s .agents/skills/android-device-verification/scripts -p 'test_*.py' -v
```

技能静态校验使用 skill-creator 的 `quick_validate.py`。本次工具的实际检查见 [验证记录](verification.md)。helper 的实机兼容性需要下一轮已授权 debug 验收确认，不因为离线回归通过而记为实机通过。
