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
