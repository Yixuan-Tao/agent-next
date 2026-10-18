# brain（纯 JVM 领域骨架）

新任务大脑的第一层骨架，**不依赖 Android**。当前实现了领域模型、FastLoop/SlowLoop 骨架、可选 skill 层（workflow + prompt 模型端口）、旧模型 chat-completions API 移植、host 场景测试、真机京东搜索 smoke、真实模型 prompt skill smoke 和视觉 verify/extract；社区 registry、完整动态重规划尚未完成。`device-api` 已作为依赖并被 `FastLoop` 使用。

## 当前包含

| 包/类型 | 作用 |
|---|---|
| `AutonomyLevel` / `ActionKind` / `AutonomyGrant` / `AutonomyPolicy` | A0–A4 自主级别与动作许可；A4 在 v1 由 `TaskSpec` 拒绝 |
| `TaskSpec` / `Budget` / `SuccessCriterion` | 目标、约束、成功标准、允许 App、预算 |
| `Subgoal` / `Condition` / `Fallback` / `TaskGraph` | 可修改的任务图：依赖、就绪、完成、失败、跳过、替换、删除；subgoal 带 targetPackage/verifierId |
| `plan.TaskPlanner` / `PlanningRequest` / `TaskPlan` / `DeepSeekTaskPlanner` | 自然语言 → 严格 JSON 计划：1..8 subgoal、allowed/installed App、verifier、澄清问题 |
| `session.SessionRunner` / `SubgoalRunner` / `TargetBinder` | 按 subgoal targetPackage 串行切换 target；每 subgoal 独立 verifier，主图 slow-loop |
| `session.SubgoalVerifier` / `VerifierRegistry` | verifier allowlist；未知 verifier fail closed |
| `session.PromptSessionPolicyFactory` / `VisualSubgoalVerifier` | subgoal → PromptSkillPolicy；StageData → prompt；视觉 verify 条件 |
| `Skill` / `SkillStep` | 带前置/后置条件、回退、风险和成本的语义技能（旧声明式模型） |
| `skill.SkillPackage` / `SkillBody` | 可安装 skill：contract、workflow/prompt/hybrid、permissions、applicability、verifier、trust、provenance |
| `skill.SkillWorkflowPolicy` | 把语义 workflow 映射成 LoopAction，经 FastLoop RiskGate 执行；不接触 DeviceApi/坐标 |
| `skill.PromptSkillPolicy` / `SkillModel` | prompt skill 的模型端口：只返回语义 Tap/SetText/Back/Done，拒绝 high-risk 步骤；不接触 DeviceApi/坐标 |
| `model.ModelConfig` / `ChatCompletionClient` | 旧 `model-config.json` + chat-completions 形状；支持文本/单帧 PNG、key 脱敏、finish_reason 校验 |
| `model.DeepSeekSkillModel` | 把模型 JSON 解析成语义 `SkillModelStep`；支持单帧 PNG；拒绝坐标字段/Point selector |
| `model.DeepSeekVisualModel` / `VisualVerification` / `StageData` | 独立视觉 verify/extract；只拿 PNG，不控制设备 |
| `skill.SkillRegistry` / `SkillValidator` / `SkillCandidate` | 信任分级与准入；社区/自生成必须验证，支持撤销 |
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
| `loop.LoopControl` / `LoopStoppedException` | 暂停检查点与用户停止；恢复后重新 observe，不复用旧动作 |

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
- `RISK_GATE` 的 Confirm 走 `HumanGateway`，Handoff 返回 `NEED_USER`；`LoopControl` 在步骤边界暂停，停止返回 `STOPPED`。
- 16 个 host suite 中的 `LoopTest`/`SlowLoopTest`/`SkillFeatureTest`/`ModelApiTest`/`TaskPlannerTest`/`SessionRunnerTest`/`SessionBridgeTest` 覆盖快慢循环、重规划、skill、视觉验证、计划、target 切换与 verifier。

## Planning

- `DeepSeekTaskPlanner` 只用文本模型生成 `TaskPlan`，不接触设备/坐标。
- 输出 1..8 个 subgoal；`targetPackage` 必须在已安装且允许集合，首节点必须是当前 primary App；
  `verifierId` 只能来自允许集合；`dependsOn` 必须构成 DAG。
- 目标有实质歧义时只返回一个 `clarification`，不生成计划。
- `TaskPlan` 在构造时复用 `TaskGraph` 校验重复 id/未知依赖/环。
- 计划仍需 app 的计划确认；planner 不执行计划。

## Session

- `SessionRunner` 按 `Subgoal.targetPackage` 串行打开/释放 target；display 由 app 拥有。
- 每个 subgoal 走 `SubgoalRunner` + 单节点 FastLoop；完成只由 `SubgoalVerifier` 决定。
- `VerifierRegistry` 是显式 allowlist；未知 verifier fail closed。
- 跨 subgoal 保留主 TaskGraph；`SLOW_LOOP` 在主图上执行 replace/insert/remove，再继续。
- 全局 action/time 预算、attempt 上限、`LoopControl` 停止；target 在完成/失败/停止后释放。
- 每个 subgoal 完成后可调用 app 注入的 `SessionStageExtractor`，把 StageData 写入 WorkingMemory（`stage.<id>.*` 和 `stage.last.*`）；失败记录事件但不阻断主流程。
- app 计划确认、UI、真实视觉 extractor 尚未接入。

## Slow loop

`SlowLoop` 消费 FastLoop 的 `SLOW_LOOP` 结果，由 `SlowLoopPolicy` 做最小决策：

- `Continue`：保持 TaskGraph，下一次 attempt 重新跑 FastLoop。
- `Replace`：**必须保留当前 subgoal id**，替换 intent/fallback/risk，节点回到 PENDING。
- `InsertBefore`：在当前位置前插入修复/澄清节点；新节点继承当前依赖，当前节点改为依赖新节点并重启。
- `RemoveCurrent`：删除当前节点，把它的依赖重接到后续节点。
- `AskUser`：返回 `NEED_USER`。
- `Stop`：返回停止/失败。

`LoopRunner` 负责 attempt 循环：FastLoop → 若 `SLOW_LOOP` 则 SlowLoop → REPLANNED/CONTINUE 再跑，记录 `attempts`/`replans`/`totalActions`。动作/时长预算跨 attempt 汇总（用剩余预算派生每次 FastLoop 的 TaskSpec）。

## Skill layer

Skill 是**可选加速层**；没有匹配 skill 时，FastLoop/通用规划仍能工作。

- `SkillPackage`：contract、workflow/prompt/hybrid body、permissions、applicability、verifier、trust、provenance。
- `SkillWorkflowPolicy`：把语义 `Tap`/`SetText`/`Back` 映射成 `LoopAction`，交给 FastLoop 的 `RiskGate`；不拿到 `DeviceApi`/`Point`。
- `SkillSelector`：封闭的语义选择器集合（viewId/text/clickable/editable），**没有坐标**。
- `SkillRegistry`：`OFFICIAL`/`VERIFIED` 可直接安装；`COMMUNITY`/`SELF_GENERATED` 必须通过 `SkillValidator`。`SkillCandidate` 只能 propose，不能自行上线；支持撤销。
- `prompt` body 由 `PromptSkillPolicy` + `SkillModel` 执行；模型只收到 observation/prompt/参数/capability 名，只能返回语义选择器。DeepSeek chat-completions API 已移植并接入 app prompt 模式；host 测试仍用 fake transport。
- `PromptSkillPolicy` 只接受 `LOW`/`REVERSIBLE_SIDE_EFFECT` 模型步骤；高风险/未知步骤不执行，仍受 RiskGate/validation 约束。

## Model API

`model` 包移植旧仓库的 `model-config.json` + chat-completions 请求形状：

- `ModelConfig.load(Path)`：读取旧格式配置，`toString` 隐藏 key；配置只放在被忽略的 `private/`。
- `ChatCompletionClient`：`{base_url}/chat/completions`、30s 超时、1 MiB 上限、`finish_reason=stop` 校验。
- `DeepSeekSkillModel`：把模型 JSON 解析成语义 `SkillModelStep`；坐标字段/Point selector 直接拒绝。
- `DeepSeekVisualModel`：独立 `verify`/`extract`，只接收单帧 PNG，不控制设备动作。
- 当前支持文本、单帧 PNG、`verify`、`extract`；没有多帧/视频。
- 测试用 fake transport，不发起真实 API 调用；不要提交或打印密钥。

## device-api 边界

`brain` 通过 Gradle `api project(':device-api')` 依赖纯 JVM 设备端口，不 import `agent.phone.*` 或 Android 类型。端口定义见 [device-api/README.md](../device-api/README.md)。

`FastLoop` 已通过 app shell 接到 capability adapter 的真机京东目标（首页→搜索→setText→提交→结果页，DONE actions=4）；`LoopRunner` 真机 smoke 也已完成；prompt skill 用真实 DeepSeek 模型完成搜索（DONE actions=4，结果页 verifier）。

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
- 多帧/视频、社区 registry 的签名/扫描；当前只有单帧视觉/内存 registry；
- OCR/视觉策略与更多 App/异常场景的真机验收（当前只有京东搜索 smoke）；
- 持久化记忆（当前只有策略与内存实现）；
- UI、确认卡、接管界面。

边界见 [决策记录](../docs/decisions.md) 与 [Loop 重设计](../docs/loop-redesign.md)。
