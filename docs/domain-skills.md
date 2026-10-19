# Domain skills 与通用 Agent 边界

## 原则

- 核心 runtime 只依赖 `DomainSkill` 接口；
- 具体 App 的 policy/verifier/prelude 放在 domain skill；
- 没有 domain skill 时，仍走通用 `PromptSkillPolicy` + 控件/视觉 fallback；
- domain skill 不能获得额外设备权限，所有动作仍走 `DeviceApi` / `InputController`。

## 结构

```text
AgentRuntime
  -> DomainSkills.create(client)       // composition root
  -> DomainSkill.matches(...)           // profile selection
  -> DomainSkill.prepareTarget(...)     // app-specific target prep
  -> DomainSkill.policy(SkillContext)   // app-specific policy
  -> DomainSkill.verifier(fallback)     // app-specific verifier
  -> PromptSkillPolicy / VisualSubgoalVerifier (fallback)
  -> DeviceApi / Shizuku
```

新增文件：

- `DomainSkill`
- `DomainSkills`
- `SkillContext`
- `JdDomainSkill`

`AgentRuntime` 已移除所有 `com.jingdong` 判断和 `Jd*` import。

## JD skill 包含

- JD home/Babel 恢复；
- 搜索入口/提交；
- 搜索结果卡视觉 grounding；
- 详情加购 policy；
- 购物车 policy/verifier。

这些都是 JD 适配器，不在 core。

## 真机验证

JD 购物车任务：

```text
DONE · all subgoals verified actions=0 attempts=4
```

说明 domain skill 路由/目标准备/verifier 在解耦后仍工作。

## 仍未完成

- JD 自动加购（s2/s3 仍不稳定）；
- Quark/Calendar 仍是 core 内联，尚未迁入 domain skill；
- domain skill 目前是 Java 实现，下一步可改为 AppProfile 声明式配置。

## 全量 domain skill 抽取

- JD → `JdDomainSkill`
- Quark 天气 → `QuarkDomainSkill`
- Calendar → `CalendarDomainSkill`

`AgentRuntime` 现在没有任何：
- `Jd*` import/字段；
- `com.jingdong` / `com.quark` / `com.android.calendar` 判断；
- `CalendarStore` / `CalendarPolicy` / `QuarkWeatherPolicy` 直接引用。

核心只通过 `DomainSkills.create(context, client)` 和 `DomainSkill` 接口路由。

真机回归：

```text
JD cart: DONE · all subgoals verified actions=0 attempts=3
target prepared domain=true com.jingdong.app.mall
```

## AppProfile 声明式标签

新增：

- `AppProfile`
- `JdAppProfile.PROFILE`

JD 的搜索入口/提交、结果卡、加购、确认、购物车、负面标签、详情标记、verifier id
都移到 profile；policy 不再直接写这些集合。

搜索/加购 policy 已接收 profile：

- `JdSearchPolicy(..., AppProfile)`
- `JdAddPolicy(AppProfile)`
- JD visual locator 使用 profile 的 allow/forbid 标签。

真机：profile 化后 JD target prepare/购物车 tab 仍工作；
本轮 s3 verifier 有波动，未作为通过。

## 通用 policy 抽取

重命名/抽象：

- `JdSearchPolicy` → `SearchPolicy(profile)`
- `JdAddPolicy` → `AddToCartPolicy(profile)`
- `JdCartPolicy` → `CartWaitPolicy`
- `JdResultPolicy` → `ResultCardPolicy(candidates, detailMarkers)`

`ResultCardPolicy` 现在持有多个视觉候选，并按顺序点击；一旦 observation 出现
profile 的 detailMarkers（如“加入购物车/商品详情”）就停止。失败会尝试下一个候选。

JD skill 通过这些通用 policy + `JdAppProfile` 运行。

## 视觉 prompt 进入 profile

`AppProfile` 新增：

- `searchEntryPrompt`
- `searchSubmitPrompt`
- `resultCardPrompt`

`SearchPolicy` 和 JD domain skill 不再硬编码视觉 prompt。

## 仍待修

- JD cart s2 verifier 波动：视觉选中的购物车 tab 有时未打开购物车；
- 结果卡候选点击后的详情校验；
- 自动加购端到端。

## 购物车候选重试

`prepareJdCart` 不再只点一个视觉点：

- 获取多个购物车 tab 候选；
- 逐个点击；
- 每次重新 observe；
- 只有出现 `cart.feature` / 合计 / 去结算才停止；
- 都不行则放弃，不盲点。

仍待真机验证 s2 cart verifier。
