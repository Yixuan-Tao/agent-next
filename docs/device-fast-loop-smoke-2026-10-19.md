# FastLoop 真机 smoke（2026-10-19）

状态：**PASS**。范围：`agent.next.app` shell → `BlockingDeviceApi` → 京东目标 → `FastLoop`。这是确定性快循环第一次在真实设备上完成一个子目标，不是完整 loop 或模型验收。

## 环境

同 [device-adapter-smoke-2026-10-19.md](device-adapter-smoke-2026-10-19.md)：Redmi Pad Pro / Android 16 / API 36，Shizuku 13.6，京东 16.1.0。

## 流程

```sh
adb shell am start -n agent.next.app/.ShellActivity --es mode loop
```

`FastLoop` 用 `JdSearchPolicy`：

1. 首页没有 editable 节点 → `TapPointAction(360,180)` 点京东搜索栏（该栏不在 accessibility 树里，smoke 用固定坐标）。
2. 搜索页 editable 为空 → `SetTextAction("短袖T恤 男款 XL")`。
3. 搜索页 editable 非空 → `TapAction("搜索")`。
4. 结果页暂时是骨架屏 → `WaitAction` 等待加载。
5. 出现 `全部 / 新品 / 今日上新 / 奥莱 / 明日达` 等筛选项 → `complete=true`。

## 结果

```text
fast-loop: connect
fast-loop: display cb765ddc-...
fast-loop: target 23c01b8f-...
policy next: home, tap search bar; READY nodes=96
policy next: type query; READY nodes=96
policy next: submit; READY nodes=96
policy next: wait for results; READY nodes=51
policy complete: READY nodes=96 texts=1|短袖T恤 男款 XL|全部|新品|今日上新|店铺|奥莱|...
fast-loop result: DONE actions=4 detail=goal complete
FAST LOOP PASS
fast-loop target released
fast-loop display released
```

- `FastLoop` 4 个动作完成：打开搜索、输入、提交、等待/确认结果。
- `EffectEvaluator` 正确识别输入变化、页面跳转和加载后的结果页。
- 释放后无 `agent-phone` display，主屏焦点回到 shell。
- 窗口切换观察修复生效：没有 `MALFORMED`，服务端无 target failure。

## smoke 专用妥协

- `TapPointAction`：京东搜索栏不可访问，固定 `(360,180)`。这是 app smoke 的 escape hatch，不在 brain 主路径。
- `WaitAction`：结果页骨架屏期间空等 700ms；深 smoke 用较高 `Budget`（no-effect 10）避免提前 slow loop。
- `JdSearchPolicy.complete` 用京东结果页筛选项文本作为证据；真实 brain 应由领域 verifier/证据模型判断，而不是写死这些标签。

## 未覆盖

- 慢循环重规划、模型决策、真实技能库；
- 更多 App/页面、异常/风控/支付；
- preview mirror + 触摸接管；
- 20 分钟并发。

## 证据

本机忽略目录 `work/device-regression/app-shell/`：

- `fast-loop-pass.log` / `fast-loop-pass.png`
- `fast-loop-results.png`（最后一帧）
