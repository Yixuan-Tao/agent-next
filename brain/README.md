# brain（纯 JVM 领域骨架）

新任务大脑的第一层骨架，**不依赖 Android**。当前实现了领域模型、确定性快循环骨架和 host 场景测试；慢循环重规划、模型调用、技能库尚未接入。`device-api` 已作为依赖并被 `FastLoop` 使用。

## 当前包含

| 包/类型 | 作用 |
|---|---|
| `AutonomyLevel` / `ActionKind` / `AutonomyGrant` / `AutonomyPolicy` | A0–A4 自主级别与动作许可；A4 在 v1 由 `TaskSpec` 拒绝 |
| `TaskSpec` / `Budget` / `SuccessCriterion` | 目标、约束、成功标准、允许 App、预算 |
| `Subgoal` / `Condition` / `Fallback` / `TaskGraph` | 可修改的任务图：依赖、就绪、完成、失败、跳过、替换、删除 |
| `Skill` / `SkillStep` | 带前置/后置条件、回退、风险和成本的语义技能 |
| `Effect` / `StateFingerprint` / `EffectEvaluator` | 动作前后状态比较：Changed/NoChange/Loading/Error/Navigated |
| `RiskClass` / `ActionDescriptor` / `RiskDecision` / `DefaultRiskGate` | 风险分类与 Allow/Confirm/Handoff/Deny |
| `ConfirmationRequest` / `ConfirmationToken` | 承诺确认；token 绑定 payload hash，内容变化即失效 |
| `MemoryPolicy` / `WorkingMemory` / `EpisodicMemory` / `TaskEvent` | 记忆保留策略与最小内存实现 |
| `HumanGateway` / `HandoffMode` / `HandoffRequest` | 预览触摸、主屏接管、凭据接管的人机边界 |
| `SlowLoopSignals` / `SlowLoopReason` | 慢循环触发条件（无效果、无进展、图不一致、风险、预算…） |
| `loop.FastLoop` / `LoopPolicy` / `LoopContext` / `LoopResult` | 确定性快循环：感知→完成检查→策略→风险门→执行→效果→记忆→慢循环升级 |
| `loop.LoopAction` / `TapAction` / `SetTextAction` / `BackAction` | 语义动作，按当前 observation 解析目标 |
| `loop.NodeSelector` / `ObservationFingerprint` | 选择器与稳定状态指纹（不包含 observation id/ref） |

## Fast loop

`FastLoop` 是一个**可测试的确定性骨架**，不调用模型：

```text
observe → policy.complete? → policy.next → risk gate
        → action.execute → observe → EffectEvaluator
        → memory / budget → SlowLoopSignals
```

- 动作通过 `LoopPolicy` 选择；测试用脚本策略，后续可替换为模型决策。
- 成功由 `LoopPolicy.complete` 的证据检查推进 TaskGraph，模型自评不算完成。
- `NO_EFFECT`/`NO_PROGRESS`/预算触发 `LoopResult.SLOW_LOOP`；重规划留给慢循环组件。
- `RISK_GATE` 的 Confirm 走 `HumanGateway`，Handoff 返回 `NEED_USER`。
- 10 个 host suite 中的 `LoopTest` 覆盖成功、无效果升级、预算、凭据 handoff、承诺确认。

## device-api 边界

`brain` 通过 Gradle `api project(':device-api')` 依赖纯 JVM 设备端口，不 import `agent.phone.*` 或 Android 类型。端口定义见 [device-api/README.md](../device-api/README.md)。

`FastLoop` 已使用 `DeviceApi` 的 observe/input 端口；下一步是把同一 loop 接到 capability adapter 的真机目标，再接慢循环重规划。

## 测试

不依赖 JUnit 或网络，沿用 capability 的 host test 风格：

```sh
JAVA_HOME=/path/to/jdk21 sh tools/test-brain.sh
```

或通过 Gradle（需要可写的 `GRADLE_USER_HOME`）：

```sh
./gradlew :brain:hostTest
```

## 明确不包含

- 慢循环重规划、完整 TaskGraph 动态插入/替换执行器；
- 模型调用、prompt、技能库、OCR/视觉策略；
- capability adapter 上的真机 loop 验收（adapter 真机 smoke 已有 observe/input/task move）；
- 持久化记忆（当前只有策略与内存实现）；
- UI、确认卡、接管界面。

边界见 [决策记录](../docs/decisions.md) 与 [Loop 重设计](../docs/loop-redesign.md)。
