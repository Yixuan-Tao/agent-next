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

## 2026-10-19 真机触发（部分）

用 Preview 手动进入 JD 商品卡后出现“京东验证”。关闭预览让 agent 恢复：

```text
AgentTaskService: isForeground=true, actions=2
notification title: AgentNext 需要人工接管
notification text: human verification required: 京东验证 · 在主屏完成后点“继续”
action[0]: 完成，继续
```

- target 已移到主屏，JD 验证页可见；
- 前台服务/通知正常；
- **“完成，继续”尚未实际点击成功**（MIUI 通知折叠/展开方式限制），
  因此 resume → 回私有 display 的链路仍未完全验收；
- 未尝试点击“快速验证”绕过。

结论：handoff 触发和通知链路 PASS；resume/恢复显示仍是待验收项。

## Resume 测试钩子与结果

debug manifest 将 `AgentTaskService` 设为 exported=true，便于 adb 触发：

```sh
adb shell am startservice -n agent.next.app/.AgentTaskService \
  -a agent.next.app.RESUME_TASK
```

release 仍保持 service/Activity 不导出。

本轮触发后 JD 验证页仍在主屏前台，未观察到明确的
`moveToPrivate + observe + loop resume` 日志，因此 **resume 仍未验收**；
不能把 startservice 成功返回当作 handoff 恢复成功。

## 通知修复（2026-10-19）

发现第一次 handoff 通知被 `startForeground` 的普通通知覆盖（actions=1）。
改为：

- `AgentTaskService.ACTION_HANDOFF` 由 service 自己 `startForeground` handoff 通知；
- runtime 先启动 service，再发 HANDOFF action，避免 race；
- 真机 dumpsys 已确认 `actions=2`（“完成，继续/停止”）。

后续用 debug `RESUME_TASK` 触发时仍未看到 runtime 的 `handoff resume requested` 日志，
resume 仍未验收；不能把 `am startservice` 返回成功当成恢复成功。
