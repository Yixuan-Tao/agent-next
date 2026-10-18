# Foreground task service smoke（Android 16 平板）

日期：2026-10-19  
设备：Redmi Pad Pro，Android 16 / API 36，Shizuku 13.6  
性质：Phase 3 前台服务最小闭环，不是 20 分钟压力测试

## 实现

- `AgentTaskService` 为前台服务，类型 `dataSync|specialUse`；
- 阶段：
  - “生成计划”时启动服务，保证 planner/device 连接不被后台回收；
  - 计划进入 `AWAITING_PLAN` 后停止服务（等待用户确认不需要常驻）；
  - “确认并执行”时再次启动；
  - `DONE`/`STOPPED`/`FAILED`/`NEED_USER` 后停止；
- 常驻通知：
  - 标题 AgentNext，正文为当前状态；
  - “停止” action 调用 `AgentRuntime.stop()` 并停止服务；
  - 点击通知回到 MainActivity；
- Android 13+ 请求 `POST_NOTIFICATIONS`（可拒绝；拒绝不改变任务边界）；
- 服务使用 `START_NOT_STICKY`：进程死亡不假装任务能继续。

## 实测

执行计划后：

```text
ServiceRecord{... agent.next.app/.AgentTaskService}
isForeground=true foregroundId=41 types=0x40000001
foregroundNoti=Notification(channel=agent-task ... actions=1)
```

`types=0x40000001` 对应 `dataSync|specialUse`。

任务完成后再次查询：

```text
（无 AgentTaskService）
```

同一次运行的主界面：

```text
state: RUNNING · 执行中
state: DONE · DONE · all subgoals verified
result: DONE actions=1 attempts=2
```

## 限制

- 尚未做 20 分钟后台运行、进程被杀、系统回收、Doze/省电策略测试；
- 通知只显示状态，没有暂停/恢复 action；暂停目前由 PreviewActivity 进入/退出控制；
- 服务与 runtime 同进程；进程死亡时任务不会续跑；
- 没有前台相机/麦克风/定位等敏感类型，只有任务编排所需的 dataSync/specialUse；
- Android 13 手机未连接。
