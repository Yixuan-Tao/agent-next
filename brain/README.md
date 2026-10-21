# brain：任务规划、执行策略与验证

纯 JVM 的任务领域模块，Java 17，通过 `device-api` 使用设备能力，不依赖 Android、AIDL 或 Shizuku。它承担 [纯 GUI 内部 Beta](../docs/pure-gui-beta.md) 的任务理解、快慢循环、风险决策、进展评估与证据式完成。

## 当前实现

| 包 / 类型 | 作用 |
|---|---|
| `TaskSpec` / `Budget` / `SuccessCriterion` | 目标、约束、成功条件、允许 App、自主级别与预算 |
| `Subgoal` / `TaskGraph` | 依赖、状态、替换、插入和删除重接 |
| `plan` | 严格 JSON 规划、澄清、App/verifier allowlist、DAG 校验 |
| `session` | 按子目标串行切换 App，路由 policy/verifier，提取 StageData |
| `loop` | FastLoop、SlowLoop、LoopRunner、观察指纹、语义动作和暂停检查点 |
| `skill` | workflow/prompt/hybrid 契约、语义选择器、模型端口、内存 registry 与信任门 |
| `model` | chat-completions 文本/PNG、重试与调用预算、视觉 verify/extract |
| `grounding` | 视觉候选及过滤规则 |
| 风险 / 确认 / 记忆类型 | A0–A3、风险决策、payload 确认、Working/Episodic 接口与内存实现 |

规划器当前支持 1–8 个子目标，首节点使用指定 primary App。产品 app 已接入计划审阅、模型策略、视觉 verifier 和 extractor。

## 当前执行链路与限制

```text
SessionRunner → SubgoalRunner → FastLoop
observe → verifier → policy.next → RiskGate → action.execute
        → observe → EffectEvaluator → 继续 / 升级 SlowLoop
```

- Session 由 `SubgoalVerifier` 判断子目标完成，模型的 `Done` 不直接代表成功；app 已为限定浏览器任务接入原始目标、正文和真实标题的同帧最终核验，其他任务尚未统一覆盖。
- SlowLoop 有 continue、replace、insertBefore、removeCurrent、askUser、stop。产品 runtime 当前注入停止策略，库能力不等于产品已有自主恢复。
- `noProgress`、session attempt 上限与正常多步骤任务存在冲突，需拆分进展、初次执行和重试。
- 语义动作通过 `ActionGrounder`、`GroundedAction` 和 `ActionExecutor` 重新定位；风险分类读取实际命中节点的文字、描述和 viewId，授权绑定任务、目标与节点指纹。它仍不等于完整交易上下文或通用安全保证。
- `LoopControl` 当前是步骤检查点，暂停后丢弃旧 action、网络取消和组合动作取消尚需贯通。
- StageData 当前是少量字符串键值；下游必需字段检查、任务隔离与旧键清理需加强。
- app 有加密 episodic 实现；brain 本身不承担 Android 存储，其内存实现不代表整个产品没有持久化。

## 契约接线与剩余目标

在现有模块中逐步实现以下行为，不新建第二套 loop：

1. 已有 `TaskContract` 与 `PlanValidator`，app 在计划审阅前接线；通用重规划的必需结果保留仍需完善。
2. 已有 `ActionProposal` → `GroundedAction` → `ActionExecutor`。`WebTextContains` 限制于稳定完整观察下的唯一可见 WebView 文本，模型仅见操作资格提示，不能提交坐标。
3. `ExecutionContext` 贯通取消、deadline、会话版本与预算，组合技能逐步治理。
4. `VerificationResult` 区分满足、不满足和未知；任务级核验生成 `TaskReport`。
5. 使用预期效果、条件进展和循环检测，接入有界恢复；权限扩大需要重新确认。

已有类型不代表所有产品路径均完成接线。预算、恢复顺序和完成状态见 [目标说明](../docs/pure-gui-beta.md)。

## Skill 与模型边界

- Skill 是可选 GUI 加速层，不直接访问业务 provider、坐标或 shell。
- 文本/截图内容作为不可信数据，不能修改任务权限或风险边界。
- 视觉核验是独立调用，但仍是模型证据，不能用调用分离替代正确性验证。
- registry 当前是内存机制；社区分发、签名和自主 skill 上线不属于本期。
- 模型接口当前为文本和单帧 PNG，不包含多帧/视频。

## 测试

从仓库根目录运行：

```sh
QUAL_JAVA_HOME=/path/to/jdk21 sh tools/test-brain.sh
# 或使用已配置的 Gradle 环境
./gradlew :brain:hostTest
```

host 测试使用 fake device/model transport，不访问真实模型或手机；suite 数以当前 `BrainHostTest` 输出为准。纯 GUI 通用性必须由 [设备验收](../docs/pure-gui-beta.md) 另行证明。
