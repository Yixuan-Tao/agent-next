# testkit（从旧仓库复制）

这些是测试软件/诊断工具，**不是产品代码**。来源：`agent-phone` 的 `tools/qualification`、`work/foreground-20261016/program`、`work/*-probe`、`work/model-loop`。已去掉构建产物、APK、原始截图/日志/设备序列号；只保留源码、脚本和 fixture。

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
