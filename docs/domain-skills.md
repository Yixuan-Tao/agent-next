# GUI skill 与通用执行边界

状态：2026-10-20，按 [纯 GUI Beta](pure-gui-beta.md) 更新。当前 `DomainSkill` 是迁移起点，以下目标约束尚未全部落实到代码。

## 产品定位

Skill 用于复用经过验证的 GUI 操作：搜索、填写字段、滚动查找、选择选项、保存草稿和重新打开核验。它是可选可靠性层；没有 App 专用 skill 时，通用策略仍须运行。

App profile 可提供 view ID、文本/描述、页面条件与候选线索；不能把固定坐标序列当作通用能力。策略输出语义目标，受信任定位层结合当前观察解析控件或视觉候选。

## 当前实现与迁移

| 当前接口 / 路径 | 迁移目标 |
|---|---|
| `DomainSkills.create` 注册 Java domain 实现 | 仅注册纯 GUI skill，保留无 skill 的通用 fallback |
| `DomainSkill.prepareTarget` 可直接调用设备 | 提出受治理的准备动作，无独立输入通道 |
| `SkillContext` 暴露 `BlockingDeviceApi` | 仅提供任务/观察/阶段数据和语义动作能力 |
| `fixedPlan` 可替换整份计划 | skill 仅实现分配的子目标，不删除其他用户要求 |
| CalendarContract / MediaStore / AlarmClock | 退出产品路由，保留历史参考，重新通过 GUI 验收 |
| JD prelude 和计算器连续点击 | 内部步骤纳入统一风险、取消、预算和回执 |
| 页面标题/节点数量判定完成 | 按指定对象及属性提供条件证据，任务级独立核验 |

当前 registry 仍包含上述直接接口路径，不能因文档更新就宣称已经禁用。

## 目标执行契约

```text
TaskContract / 当前子目标 / 观察
  → 通用策略或匹配 GUI skill → ActionProposal
  → 受信任定位 → GroundedAction
  → ActionExecutor（取消、风险、确认、预算）
  → GUI 输入 → 新观察 → 条件证据
```

- Skill 不持有 `DeviceApi`、shell、业务 provider 或任意执行回调；坐标仅在受信任层流转。
- 适用性同时检查任务意图、参数和页面前置条件，不能仅凭包名把任何任务变成固定操作。
- 准备步骤、候选重试、返回和组合动作均使用统一执行器。
- 风险自报不可信；LOW 不能绕过承诺确认或凭据接管。
- 恢复后重新观察；副作用结果未知时先核验，不能重放。
- verifier 不执行动作，不修改目标，不把“购物车页面存在”当成“指定商品已加购”。

## 验收与历史证据

开发集验证纯 GUI 场景；通用保留集禁用对应 App 专用 profile/policy/workflow，单独统计。历史 `DONE actions=0` 可能是页面或旧结果本来已存在，不能证明本轮完成了创建或加购。

历史记录：[Chain B](chain-b-attempt-2026-10-19.md)、[日历 domain](calendar-domain-skill-2026-10-19.md)、[日历/目录任务集](agent-task-suite-2026-10-20.md)、[计算器探索](calculator-visual-2026-10-20.md)。直接接口结果不进入纯 GUI 成绩。社区 registry 和自生成 skill 上线不属于本期。
