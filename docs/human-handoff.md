# 人工接管 / 主屏 handoff

状态：代码接入；真实 JD 风控 handoff 待验收

## 触发

`RiskGuardPolicy` 检测到：

```text
京东验证 / 安全验证 / 人机验证 / 验证码 / 滑块验证 / CAPTCHA /
verify you are human / unusual activity
```

## 流程

1. session worker 停在当前步骤（不执行风险页动作）；
2. 前台服务通知变为“需要人工接管”，带两个 action：
   - 完成，继续
   - 停止
3. target 移到主屏，用户手动完成验证/操作；
4. 用户点“完成，继续”后：
   - `AgentTaskService.ACTION_RESUME` → `AgentRuntime.resumeHandoff()`；
   - target 移回私有 display；
   - 重新 `observe`；
   - RiskGuard 返回一个 bounded wait，loop 继续。
5. 用户点“停止”或 15 分钟超时：
   - handoff 返回 false；
   - `HumanHandoffRequiredException` → `NEED_USER`，不执行动作。

## 安全边界

- 不识别/绕过验证码、滑块、风控；
- 不自动点“立即购买/支付”；
- 用户未确认时不会自动恢复；
- 停不下来时以 NEED_USER 结束，不伪造 DONE。

## 待验收

- 真实 JD 风控页 → 通知 → 用户完成验证 → 返回私有 display 的完整链路；
- 主屏期间 Activity 生命周期/服务存活；
- 超时和停止 action；
- Android 13 通知权限/前台服务类型。
