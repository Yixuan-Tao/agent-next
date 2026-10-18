# brain（纯 JVM 领域骨架）

新任务大脑的第一层骨架，**不依赖 Android**。当前实现了领域模型、FastLoop/SlowLoop 骨架、host 场景测试和真机京东搜索 smoke；模型调用、技能库、完整动态重规划尚未接入。`device-api` 已作为依赖并被 `FastLoop` 使用。

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
| `loop.SlowLoop` / `SlowLoopPolicy` / `SlowLoopDecision` | 慢循环决策：continue / 同 id replace / ask user / stop |
| `loop.LoopRunner` / `LoopRunResult` | 多次 FastLoop 尝试 + slow-loop 重规划计数 |

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
- 11 个 host suite 中的 `LoopTest`/`SlowLoopTest` 覆盖成功、无效果升级、预算、凭据 handoff、承诺确认、replace/ask/stop/continue 与 runner 重规划。

## Slow loop

`SlowLoop` 消费 FastLoop 的 `SLOW_LOOP` 结果，由 `SlowLoopPolicy` 做最小决策：

- `Continue`：保持 TaskGraph，下一次 attempt 重新跑 FastLoop。
- `Replace`：**必须保留当前 subgoal id**，替换 intent/fallback/risk，节点回到 PENDING。
- `InsertBefore`：在当前位置前插入修复/澄清节点；新节点继承当前依赖，当前节点改为依赖新节点并重启。
- `RemoveCurrent`：删除当前节点，把它的依赖重接到后续节点。
- `AskUser`：返回 `NEED_USER`。
- `Stop`：返回停止/失败。

`LoopRunner` 负责 attempt 循环：FastLoop → 若 `SLOW_LOOP` 则 SlowLoop → REPLANNED/CONTINUE 再跑，记录 `attempts`/`replans`/`totalActions`。动作/时长预算跨 attempt 汇总（用剩余预算派生每次 FastLoop 的 TaskSpec）。

## device-api 边界

`brain` 通过 Gradle `api project(':device-api')` 依赖纯 JVM 设备端口，不 import `agent.phone.*` 或 Android 类型。端口定义见 [device-api/README.md](../device-api/README.md)。

`FastLoop` 已通过 app shell 接到 capability adapter 的真机京东目标（首页→搜索→setText→提交→结果页，DONE actions=4）；`LoopRunner` 真机 smoke 也已完成（第一轮 NO_EFFECT → replace → 第二轮成功，DONE attempts=2 replans=1 totalActions=6）。

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

- 任意 DAG 重排、多技能组合、条件 verifier；当前只有 replace / insertBefore / removeAndRewire；
- 模型调用、prompt、技能库、OCR/视觉策略；
- 更多 App/异常场景的真机验收（当前只有京东搜索 smoke）；
- 持久化记忆（当前只有策略与内存实现）；
- UI、确认卡、接管界面。

边界见 [决策记录](../docs/decisions.md) 与 [Loop 重设计](../docs/loop-redesign.md)。
