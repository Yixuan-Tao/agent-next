# Slow-loop / LoopRunner 真机 smoke（2026-10-19）

状态：**PASS**。范围：`agent.next.app` shell → `LoopRunner` → 第一轮受控失败 → `SlowLoop` 替换 approach → 第二轮京东搜索成功。验证了慢循环、跨 attempt 预算和真机动作的组合，不是产品级自主恢复。

## 环境

同 [device-adapter-smoke-2026-10-19.md](device-adapter-smoke-2026-10-19.md)：Redmi Pad Pro / Android 16 / API 36，Shizuku 13.6，京东 16.1.0。

## 场景

```sh
adb shell am start -n agent.next.app/.ShellActivity --es mode runner
```

- 第一轮 `NoOpPolicy`：故意执行 2 个空动作，产生 `NO_EFFECT`（受控失败，不是随机故障）。
- `SlowLoopPolicy` 看到 `NO_EFFECT`，对当前 subgoal `search` 做同 id `Replace`，换成 “search after replan”。
- 第二轮 `JdSearchPolicy`：首页 → 搜索 → `setText` → 提交 → 等待结果页 → 完成。
- 任务预算 `maxActions=40`、`maxConsecutiveNoEffect=2`；第一轮 2 个动作，第二轮 4 个动作，跨 attempt 总计 6。

## 结果

```text
runner: attempt 1 policy=no-op current=search
runner: slow reason=NO_EFFECT actions=2
runner: attempt 2 policy=JD search current=search
runner result: DONE attempts=2 replans=1 totalActions=6 detail=goal complete
LOOP RUNNER PASS
runner target released
runner display released
```

- `LoopRunner` 保留 TaskGraph，`SlowLoop` 替换同 id 节点后第二轮成功。
- 跨 attempt 预算累计：`totalActions=6`。
- 释放后无 `agent-phone` display，主屏焦点回到 shell。

## 受控失败说明

第一轮空动作是为了稳定触发 `NO_EFFECT`，验证重规划链路；它不代表真实故障恢复能力。真实场景应来自观察/效果/风险信号，而不是脚本故意失败。

## 未覆盖

- `InsertBefore` / `RemoveCurrent` 目前只有 host 测试，没有真机场景；
- 模型驱动的 slow-loop 决策；
- 真实网络/风控/登录/支付场景；
- 任意 DAG 重排、多技能组合；
- preview mirror + 触摸接管、20 分钟并发。

## 证据

本机忽略目录 `work/device-regression/app-shell/`：

- `loop-runner-pass.log` / `loop-runner-pass.png`
- `loop-runner-results.png`

相关：[FastLoop 真机 smoke](device-fast-loop-smoke-2026-10-19.md)
