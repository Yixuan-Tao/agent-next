# Agent Loop 重设计（新项目提案）

状态：**设计提案，未实现**。这份文档描述新项目的目标 loop，不是当前代码的说明。当前实现仍是固定计划 + 单步 observe→act 循环；不要用本文反向解释旧代码。

## 1. 为什么重做

当前 loop 的核心问题不是动作少，而是缺少人类操作手机时的这些能力：

- 没有技能：每轮重新看全屏、重新决定一个原子动作；
- 没有工作记忆：不知道自己在哪个页面、刚才试过什么；
- 没有等待/稳定态：加载中也当成可操作页面；
- 没有效果验证：点完不检查是否发生变化；
- 没有纠错/重规划：卡住就停，计划确认后固定；
- 没有风险模型：靠 forbidden 关键词和人工兜底；
- 没有不确定性：证据不足时仍继续猜；
- 没有真正的接管体验：验证码/支付/偏好只能中断任务。

旧 loop 的证据只能证明“底层能操作”，不能证明“会办事”。

## 2. 人是怎么操作手机的

1. **先有意图和验收标准**：买短袖 T 恤、XL、预算内、加入购物车；不需要用户手填条件。
2. **有工作记忆**：知道当前 App、页面、刚才做了什么、下一步是什么。
3. **用技能，不用原子动作**：搜索、滚动查找、选规格、填表、加购、切换 App 都是技能。
4. **选择性注意**：忽略广告/横幅，找相关控件和文字。
5. **会等待**：加载时等稳定，不连续乱点。
6. **会验证效果**：点完看页面有没有变化；没变化就重试、返回或换路径。
7. **会纠错**：点错会返回；找不到会换关键词；页面变了会重规划。
8. **知道何时问人**：登录、验证码、支付、删除、发消息、权限、偏好。
9. **随时可被打断**：接电话、用户抢手机；回来后继续，不丢上下文。

## 3. 设计原则

- **双层循环**：慢循环负责目标/任务图/重规划/风险；快循环负责感知/技能/执行/效果验证。
- **事件驱动**：页面稳定、加载完成、弹窗、用户接管、通知都可以触发，不固定每 500ms 截屏。
- **技能优先**：技能由多个原子动作组成，但必须有前置条件、后置条件和回退。
- **效果验证**：每个技能执行后验证页面/状态变化；没有变化就是失败信号。
- **不确定性可表达**：证据不足时先看/滚/等/问，不直接猜。
- **人类承诺边界**：身份证明、最终承诺、个人偏好必须人来做。
- **可中断/可恢复**：用户随时接管，恢复时重新观察，不重放未确认副作用。
- **证据式完成**：模型自评不算完成。

## 4. 新架构

```text
用户自然语言目标
      ↓
Intent / TaskSpec
  目标 / 约束 / 成功标准 / 风险 / 自主级别 / 允许 App
      ↓
Task Graph（可修改，不是固定 1–4 段）
  子目标 + 依赖 + 前置条件 + 后置条件 + 失败回退
      ↓
┌──────────────── 快循环 ────────────────┐
│ 感知稳定状态                             │
│ → 选择技能/原子动作                      │
│ → 执行                                   │
│ → 验证效果（变化/加载/错误）             │
│ → 更新工作记忆                           │
│ → 卡住/风险/歧义 → 升级慢循环            │
└────────────────────────────────────────┘
      ↓
慢循环：进度评估 / 重规划 / 询问 / 确认 / 停止
      ↓
完成验证 → 必要时人工确认 → 结果/审计
```

### 4.1 TaskSpec / Intent

```text
TaskSpec
  goal: 用户自然语言目标
  constraints: 明示约束（预算、尺寸、时间、地址、收件人…）
  success: 自动提取的成功标准 + 必要时用户确认
  autonomy: A0–A4
  allowed_apps: 默认全部可启动 App，减去用户取消项
  risk_policy: 哪些动作需要确认/硬 handoff
  budget: 动作/时间上限（动态但有硬上限）
```

不要求用户手填完成条件。成功标准由模型从自然语言提取；有歧义时问一个最小问题，或选择安全默认并在最终确认时说明。

### 4.2 Task Graph

不是固定阶段列表，而是可修改的图：

- 节点：子目标；
- 边：依赖/顺序；
- 每节点：前置条件、后置条件、可选技能、失败回退、风险等级；
- 跨 App 仍是串行，但图可以重排、插入“澄清/接管/重试”节点；
- 计划确认是高层确认，不是每个动作确认。

### 4.3 快循环

状态：

```text
PERCEIVE → STABLE → DECIDE → ACT → VERIFY_EFFECT
                ↑                         │
                └──── RECOVER / REPLAN ───┘
```

- **PERCEIVE**：UI 树 + 截图 + OCR + App/Activity/窗口身份；
- **STABLE**：等待布局/动画/网络稳定，检测加载、骨架屏、弹窗；
- **DECIDE**：选技能或原子动作；输出必须带预期效果和置信度；
- **ACT**：执行一个技能（可包含多个底层动作）；
- **VERIFY_EFFECT**：比较前后状态；Changed / NoChange / Loading / Error / Navigated；
- **RECOVER**：重试、回退、滚动、换路径、问人；
- **REPLAN**：慢循环触发。

### 4.4 慢循环

触发条件：

- 连续 2 次无效果；
- 连续 3 次无进展；
- 页面/任务图不一致；
- 需要新 App/新权限；
- 风险门触发；
- 成功标准有歧义；
- 预算接近上限；
- 阶段完成，需要评估下一节点。

慢循环输出：

- 继续当前节点；
- 换技能/换路径；
- 插入澄清节点；
- 请求用户确认/接管；
- 重规划；
- 停止/部分完成。

### 4.5 技能

技能是“有语义的一段操作”，不是固定点击脚本：

```text
Skill
  id
  preconditions（当前 App/页面/控件/登录态）
  steps（可调用原子动作或其他技能）
  postconditions（预期页面/数据/可见结果）
  fallback（返回/滚动/换关键词/问人）
  risk
  cost
```

示例：

- `open_app`
- `search(query)`
- `scroll_to_find(text)`
- `open_first_matching_result`
- `select_option(label)`
- `fill_field(label, value)`
- `add_to_cart(sku)`
- `switch_app(package)`
- `read_screen`
- `wait_until_stable`
- `draft_message`
- `prepare_checkout`（只能准备，不能提交）

**技能库不是 App 专用点击序列**；它描述语义和验证条件，具体控件由当前观察决定。

### 4.6 感知

- 屏幕状态不是单帧，而是 `ScreenState`：App、Activity、窗口、可访问性树、截图、OCR、稳定度、置信度；
- 事件驱动 + 稳定检测；加载中不决策；
- 变化检测：动作前后 diff；
- 敏感窗口/非目标窗口/安全页拒绝读取；
- 密码字段不返回文本；
- 视觉/OCR 是证据，不是唯一事实。

### 4.7 工作记忆

- **Working**：当前 TaskGraph 节点、页面、已尝试技能、失败次数、待确认问题；
- **Episodic**：本次任务的动作/观察/结果/接管记录；
- **Semantic**：可复用的 App 导航知识/技能（需验证，不写死坐标）；
- **User**：偏好/约束（地址、尺码、预算）；敏感数据最小化、可清除。

记忆不是把整屏文本无限塞进 prompt；它是结构化状态 + 可检索证据。

### 4.8 效果验证与完成

- 每个技能有后置条件；执行后验证；
- 任务级成功标准独立于模型自评；
- 证据：可见文字、OCR、视觉、结构化数据、领域 verifier；
- 领域 verifier 是插件（购物车、日历事件、消息已发送），不是第二套 loop；
- 没有证据 → `PARTIAL/NEED_USER`，不报 `DONE`。

### 4.9 风险门

风险分级在**动作前**判断：

- 可逆、低风险：自动；
- 有副作用但可逆：执行 + 记录；
- 承诺/不可逆：确认；
- 身份/验证/法律/医疗：硬 handoff。

风险门不是关键词列表，而是“动作 + 目标 + 上下文 + 用户策略”的分类；规则 + 模型结合，规则优先。

### 4.10 人类网关

- 实时预览 + 直接触摸接管（已有能力可复用）；
- 确认卡：说明要做什么、后果、可撤销性；
- 暂停/继续/停止；
- 接管后保留上下文；恢复前重新观察；
- 用户修改目标/约束时，慢循环重规划，不继续旧计划。

## 5. 人类介入分类

### A. 硬性必须人类

Agent 无法合法/可靠替代：

- 设备解锁、密码、生物识别、Passkey；
- CAPTCHA、风控验证、人脸/身份证核验；
- 短信/邮箱 OTP、另一台设备确认；
- 法律签署、实名认证、医疗/法律判断；
- 必须真人对话的客服/电话；
- NFC 碰一碰支付、实体卡/硬件操作；
- 恢复出厂、root、SIM/网络/系统更新等系统级高风险操作。

### B. 可准备，但必须用户确认

Agent 可以做到提交前一步：

- 支付、下单、转账、订阅、预订、竞价；
- 发送消息/邮件/评论/发帖；
- 删除数据/文件/账号、卸载、清空；
- 权限：定位、相机、麦克风、通讯录、无障碍；
- 安装未知 App、跨 App 分享个人数据；
- 接受有法律/费用后果的条款；
- 修改账号/隐私/安全设置。

### C. 必须问偏好/消除歧义

- 选哪个商品、颜色、尺码、数量；
- 配送地址、时间、收件人；
- 预算/价格取舍；
- 同等候选之间的选择；
- 目标本身不明确。

### D. 可以自主完成

- 打开 App、搜索、滚动、看结果；
- 填非敏感表单；
- 比较、加购物车；
- 返回/切换页面；
- 准备草稿/复制；
- 读取当前界面。

## 6. 自主级别

| 级别 | 行为 | 例子 |
|---|---|---|
| A0 只读 | 只看/总结 | 读页面、比价 |
| A1 导航 | 可点/滚/搜索，不提交 | 找到商品、打开详情 |
| A2 可逆执行 | 加购、填表、准备订单 | 默认 |
| A3 提交需确认 | 支付/发送/删除前停 | 下单、发消息 |
| A4 低风险自动 | 仅明确授权的可逆动作 | 自动加购 |

默认 A2/A3；A4 需要显式任务级授权，不是全局默认。

## 7. 接口草案

```kotlin
data class TaskSpec(
    val goal: String,
    val constraints: List<Constraint>,
    val success: SuccessCriteria,
    val autonomy: AutonomyLevel,
    val allowedApps: Set<String>,
    val riskPolicy: RiskPolicy
)

data class Subgoal(
    val id: String,
    val intent: String,
    val dependsOn: Set<String>,
    val preconditions: List<Condition>,
    val postconditions: List<Condition>,
    val fallback: Fallback
)

data class Skill(
    val id: String,
    val preconditions: List<Condition>,
    val steps: List<Step>,
    val postconditions: List<Condition>,
    val risk: RiskLevel,
    val cost: Int
)

data class Observation(
    val app: String,
    val activity: String,
    val displayId: Int,
    val windowId: Int,
    val nodes: List<Node>,
    val screenshot: Frame?,
    val stable: Boolean,
    val confidence: Float
)

sealed class Effect {
    object Changed : Effect()
    object NoChange : Effect()
    object Loading : Effect()
    data class Error(val reason: String) : Effect()
    data class Navigated(val app: String, val activity: String) : Effect()
}

sealed class RiskDecision {
    object Allow : RiskDecision()
    data class Confirm(val reason: String, val action: Action) : RiskDecision()
    data class Handoff(val reason: String) : RiskDecision()
    object Deny : RiskDecision()
}
```

## 8. 循环伪代码

```text
task = understand(goal)
if ambiguous: ask_one_question()
graph = plan(task)
if high_risk_or_new_apps: confirm()

while active:
    state = perceive_until_stable()
    if state is unsafe/secure: handoff_or_stop()
    if graph.needs_replan(state):
        graph = slow_loop(graph, state)
        continue
    skill = select_skill(graph.current, state, memory)
    risk = risk_gate(skill, task)
    if risk == Confirm: request_confirmation()
    if risk == Handoff: handoff()
    if risk == Deny: stop()
    before = state
    result = execute(skill)
    effect = verify_effect(before, perceive())
    memory.record(skill, result, effect)
    if effect == NoChange: recover()
    if graph.current.done(): graph.advance()
    if graph.done():
        verdict = verify_goal(graph)
        finish(verdict)
```

## 9. 与当前 loop 的差异

| 当前 | 新设计 |
|---|---|
| 固定 1–4 段计划，确认后不改 | TaskGraph，可重规划 |
| 每轮截图选一个原子动作 | 事件驱动 + 技能 + 效果验证 |
| 无工作记忆 | Working/Episodic/Semantic/User |
| 失败就停 | 卡住检测、回退、换路径、问人 |
| 字面条件核验 | 证据 + 领域 verifier + 人工确认 |
| forbidden 关键词 | 风险分类 + 同意门 + 硬 handoff |
| 10/16 动作固定预算 | 动态预算 + 硬上限 |
| 验证码才叫人 | 身份/承诺/偏好/风险都可介入 |
| 单步模型循环 | 快慢双层 + 技能/记忆/反思 |

## 10. 新项目验证计划

1. **能力回归**：display/target/observation/input/preview/handoff 的既有 host + 设备测试先过。
2. **Chain A（非购物）**：Quark 查天气 → 日历建提醒；验证技能、跨阶段数据、日历 verifier、重规划。
3. **Chain B（购物）**：Quark 研究 → JD 搜索/规格/加购 → 重开购物车核验；停在支付前。
4. **中断/接管**：用户中途触摸接管、主屏接管、恢复；不重放副作用。
5. **风险门**：支付/发送/删除/权限/登录的确认或 hard handoff。
6. **故障**：加载、弹窗、页面变化、无结果、网络断、服务退出。
7. **20 分钟并发**：用户持续使用主屏，Agent 后台执行。
8. **手机独立**：断开 ADB，仅手机端完成。

指标：任务成功/合理停止/人工介入分别报告；不以模型自评代替证据。

## 11. 明确不做

- 不承诺任意 App 100% 成功；
- 不自动支付/下单/发消息/删除/授权；
- 不绕过 CAPTCHA/风控/生物识别；
- 不做多 Agent 并行操作同一手机；
- 不访问 App 私有数据库/内部 API；
- 不把固定点击脚本包装成“技能”；
- 不把 JD 当产品中心。

## 12. 待决策

- 默认自主级别：A2 还是 A3？
- 发消息是否永远人工确认，还是允许 A4 低风险自动？
- 密码是否接入 Android Autofill/Credential Manager，还是全部人工？
- 记忆保存范围/保留期/清除方式。
- 新项目是独立 repo 还是 monorepo；旧 repo 是否只读冻结。
- 是否保留主屏接管作为 fallback，还是只做预览触摸接管。

---

相关：[新项目保留清单](new-project-retention.md)
