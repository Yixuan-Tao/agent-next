# Product UI / plan review smoke（Android 16 平板）

日期：2026-10-19  
设备：Redmi Pad Pro，Android 16 / API 36，Shizuku 13.6  
性质：Phase 3 第一版产品壳 + runtime 接线，不是完整 Beta

## 已完成入口

`MainActivity` 现在是 launcher：

- 自然语言目标输入；
- 主 App 选择（默认京东；实际安装才显示）；
- “生成计划”；
- 计划审阅：subgoal 的 target/intent/verifier/postconditions、task success criteria；
- “确认并执行”/“停止”；
- 高风险动作弹窗确认（`UiHumanGateway`）；
- 运行状态和 SessionResult 日志；
- PreviewActivity 入口。

`AgentRuntime` 负责：

```text
connect Shizuku -> private display
  -> DeepSeekTaskPlanner
  -> AWAITING_PLAN
  -> user confirms
  -> SessionRunner
       -> TargetBinder per subgoal
       -> PromptSessionPolicyFactory
       -> VisualSubgoalVerifier
       -> StageExtractor
  -> DONE / NEED_USER / STOPPED / FAILED
```

## 实测

```text
state: PLANNING · 规划中
state: AWAITING_PLAN · 等待确认计划
AgentNextRuntime: verify s1 -> true (matched=2, missing=0)
AgentNextRuntime: verify s2 -> false (matched=1, missing=1)
AgentNextRuntime: skill step=0 -> Done
state: RUNNING · 执行中
state: STOPPED · STOPPED · v1 stops on slow loop
result: STOPPED · v1 stops on slow loop actions=0 attempts=2
```

结论：

- 真实模型生成 4 段计划；计划审阅和人工确认边界工作；
- SessionRunner 正确切到京东 target；
- s1 视觉 verifier 通过；
- s2 只满足 1/2 个 postcondition，模型返回 `Done` 而没有动作，FastLoop 进入 slow loop；
- v1 的 `SlowLoopPolicy` 明确 `Stop`，所以结果是 **STOPPED**，不是 DONE；
- 没有执行下单/支付/发送/删除，没有错误提交。

![计划审阅](assets/main-plan.png)

## 已知缺口

- s2 的 verifier 条件/模型动作策略需要继续调优；当前会在“浏览商品”阶段停下，而不是假装完成。
- `PreviewActivity` 目前仍自己持有 API，尚未共享 `AgentRuntime` 的 display/target，也尚未在触摸时暂停 loop。
- 没有前台服务/常驻通知；Activity 关闭会中止 runtime 生命周期。
- 没有计划编辑/澄清回填；澄清只展示问题。
- 未做加密 memory/settings/隐私页。
- Android 13 手机未连接。
