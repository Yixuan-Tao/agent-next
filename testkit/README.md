# testkit：设备验证与纯 GUI 评测资产

这些是测试软件/诊断工具，**不是产品代码**。来源：`agent-phone` 的 `tools/qualification`、`work/foreground-20261016/program`、`work/*-probe`、`work/model-loop`。已去掉构建产物、APK、原始截图/日志/设备序列号；只保留源码、脚本和 fixture。

下一阶段建设目标是 [纯 GUI Beta 设备评测](../docs/pure-gui-beta.md)。本目录目前仍以既有探针和迁移工具为主，不表示完整 36 任务评测入口已经实现。

## 新版评测约定

- 布景、运行、外部核验、清理分离；执行中的 agent 只能使用 GUI，不接收 oracle 查询结果。
- 开发集 24 个任务，保留集 12 个；保留集禁用相应 App 专用 profile/policy/workflow，开发前冻结，用于修复后必须更换。
- Android 13 手机与 Android 16 设备各三轮独立统计；自主、人工辅助、合理停止、失败分开。
- 目标门槛为每台开发集自主完成率 ≥80%、保留集 ≥60%；误报完成、未经确认提交、停止后新派发输入为零。
- 初始状态可重复；已有结果不能冒充本轮创建。环境、账号和风控失败不事后剔除。
- 故障套件覆盖加载/弹窗、断网/429、target/Shizuku 退出、取消、接管、Activity 重建与进程回收；二十分钟测试必须真实执行任务并与主屏使用并发。
- 高风险承诺测试使用合成 App；不对真实账号做支付、下单、外发或不可逆删除验收。

以上是待交付的评测标准，不是当前测试成绩。旧 loop、混合接口写入、手动辅助和仅 Service 存活的结果保留为历史证据，不能合并计入新版纯 GUI 成功率。

## 目录

| 目录 | 内容 | 用途 |
|---|---|---|
| `foreground/` | 合成前台测试 App（`TestActivity`/`StateProvider`）、fixture server、driver/calibrate | 验证 Agent 后台执行时主屏焦点/键盘/文字不被影响 |
| `qualification/` | InputRequest/FrameRequest/TouchRequest/ObservationBudget/SessionPolicy 及 host 测试 | 独立准入探针 |
| `probes/task-move/` | `ActivityTaskManager` root task move 探针 | 主屏接管诊断 |
| `probes/mirror/` | `VirtualDisplayConfig.Builder` mirror 探针 | display mirror 参数诊断 |
| `probes/parcel-failure/` | 真实 Parcel/SharedMemory 失败注入 | 协议/FD 清理回归 |
| `probes/frame-memory/` | 帧内存/FD 探针 | 采样泄漏诊断 |
| `probes/frame-capture/` | 截图诊断 | frame 采集诊断 |
| `diagnostics/focus/` | 焦点/无障碍树/virtual display 诊断源码与脚本 | 主屏干扰定位 |
| `diagnostics/clipboard/` | 输入/无障碍源码与脚本 | 输入边界诊断 |
| `harness/legacy-model-loop/` | 旧 UI/旧 action schema 的布景脚本、mock JSON | **仅参考/迁移**，不代表新 loop |

## 重要说明

- 没有复制 APK、`classes/`、`dex/`、jar、原始截图、`events.jsonl`、设备日志或序列号。
- `foreground/README.md`、`qualification/README.md` 是旧环境说明；构建前按本机 Android SDK/包名适配。
- 旧 harness 的 mock action 是旧 `AgentLoop` 的 schema；新 brain 不直接使用，只迁移场景/布景。
- 旧 loop 场景证据（38 次采样、A18 1219.92s、5 轮 JD、主屏接管、mirror/touch、Quark/Planner）保留在旧仓库证据目录；本仓库只保留工具和摘要，不重新声明新 loop 通过。
- 本目录是内部测试资产，不随产品发布（见 [决策记录](../docs/decisions.md) D8）。
