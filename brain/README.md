# brain（纯 JVM 领域骨架）

新任务大脑的第一层骨架，**不依赖 Android**。当前实现可独立验证的领域模型与不变量；快慢循环、模型调用、技能库尚未接入。`device-api` 已作为依赖接好，领域层尚未调用设备。

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

## device-api 边界

`brain` 通过 Gradle `api project(':device-api')` 依赖纯 JVM 设备端口，不 import `agent.phone.*` 或 Android 类型。端口定义见 [device-api/README.md](../device-api/README.md)。

当前领域层还没有调用设备；下一步是快慢循环执行器 + `FakeDeviceApi` 的场景测试，再接已通过真机 observe/frame smoke 的 capability adapter。

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

- capability adapter 的真实设备调用：adapter host 契约与 app shell 真机 smoke（observe/frame/input/task move）已有；brain loop 尚未调用；窗口切换观察稳定性待修；
- 快循环/慢循环执行器、模型调用、prompt、技能库；
- 持久化记忆（当前只有策略与内存实现）；
- UI、确认卡、接管界面；
- 真实设备/App 验收。

边界见 [决策记录](../docs/decisions.md) 与 [Loop 重设计](../docs/loop-redesign.md)。
