# Chain B 进展与阻塞

## 已验证

- JD 搜索到结果列表；
- JD 首页任务 DONE（release：actions=2 attempts=4）；
- 手动可打开 JD 购物车，已有商品：影视飓风“凉伴”Air 短袖，星夜黑 XL，数量 3；
- 购物车页面可见商品标题/数量/合计，不结算。

## 本轮代码

新增 JD cart scoped prelude：

```java
prepareJdCart(target) // 无文本的底部购物车 tab，使用 720x1280 坐标
```

触发条件：subgoal verifier `jd.cart` 或 intent 含“购物车”。

状态：**代码已编译进新 release，但端到端 cart 任务尚未跑通**；
一次 plan 未生成（UI 仍未确认原因），因此不能宣称 cart 自动化 PASS。

## 仍阻塞

- JD 商品详情页无可操作规格/加购节点；
- 进入商品卡会触发“京东验证”，需要人工完成；
- 没有自动结算/支付。

## 2026-10-19 cart 任务结果

### 购物车已在前台

```text
result: DONE · all subgoals verified actions=0 attempts=3
```

- 购物车页面标题/商品/数量/合计均 verifier PASS；
- 但 actions=0，说明 agent 只是确认了已存在的购物车页面，
  **没有从首页导航到购物车**。

### 从 JD 首页重试

```text
result: STOPPED · v1 stops on slow loop actions=2 attempts=1
```

- cart prelude 已触发点击，但未能稳定完成后续视觉验证；
- 不能宣称 cart 导航 PASS。

结论：**购物车内容识别 PASS（已在前台时）**；**首页 → 购物车导航仍待修**。

## 2026-10-19 cart verifier 尝试

新增：

- `JdCartVerifier`：确定性检查“购物车”标题 + 商品/合计/去结算；
- `prepareJdCart` 只在标题+footer 同时存在时认为已在购物车，否则点击 scoped cart tab；
- cart tap 后 settle 1500ms。

真机日志：

```text
JD home tab selected
verify s1 -> true
JD cart tab selected
skill step=0 -> Tap selector=clickable text contains 购物车
...
```

问题：

- cart tab 点击已发生；
- 但模型仍尝试按文本找“购物车”并失败，说明 accessibility 树没有该 tab；
- 随后 s1 再次被 11.11 主会场页面干扰，任务 STOPPED。

结论：cart prelude 点击路径存在；**首页 → 购物车导航仍未 PASS**。

## 2026-10-19 确定性 cart policy

新增 `JdCartPolicy`：cart 子目标不再调用 prompt model，只返回 WaitAction，
依靠 `JdCartVerifier` 判定完成。

真机重跑仍出现：

```text
JD cart tab selected
...
FAILED action failed: REJECTED tap target not found: clickable text contains 购物车
```

说明当前 build 中仍有模型文本点击路径（或未走到 JdCartPolicy 分支），
cart 导航仍未 PASS。不得以新增类代替验证。

## 2026-10-19 policy wiring 修复

发现 `JdCartPolicy` 类已添加但 **AgentRuntime 未 import / 未 return**，因此 cart 子目标仍走 prompt model。
已修复：

```text
JD cart policy selected: s2 verifier=jd.cart
JD cart tab selected
```

但任务仍 `STOPPED actions=2 attempts=1/2`，未进入 `verify s2` PASS。
说明 cart tab 点击仍未把页面切到购物车（或 verifier 未拿到目标页面），
cart 导航仍不能算 PASS。

## 2026-10-19 诊断：s1 被 11.11 主会场阻塞

最新日志：

```text
verify s1 -> false ... 11.11主会场
verify s1 -> false ... 海运满...
verify s1 -> false ... 分享得大额券
```

- `prepareJdHome` 的 home tab 点击没有稳定回到首页；
- 任务未进入 s2，cart policy 未执行；
- 因此本轮 cart 失败根因是 **JD 启动/主会场恢复**，不是 cart policy。

新增日志：

```text
JD cart after tap act=... nodes=... text=...
verify cart s2 -> ...
```

待后续用这些日志确认 cart 页面。

## 2026-10-19 JD event 恢复 + cart 打开

`prepareJdHome` 增加 Babel/11.11 主会场检测：检测到就 back 后重试，再点 home tab。

本轮日志：

```text
JD home tab selected act= nodes=33
verify s1 -> false ... 11.11主会场
...
verify s1 -> false ... id=com.jd.lib.cart.feature:id/a15
...
```

- cart prelude 已把页面切到 **cart.feature**（购物车）节点；
- 但 s1 “进入首页” verifier 仍要求首页，因此 s1 失败，s2 没机会运行；
- 这是**计划顺序/verifier 约束**问题，不再是 cart tab 点击问题。

下一步：cart 任务不应强制 s1 首页；或 s1 在目标已是购物车时应视为满足。

## 2026-10-19 cart view-id verifier 结果

- 新增 cart.feature viewId 识别；
- cart goal 在 target 打开时预执行 `prepareJdCart`；
- s1 可以在 cart 页面通过（cart goal 特例）；
- 但最新日志：

```text
verify s1 -> true
JD cart policy selected: s2 verifier=jd.cart
JD cart after tap act= nodes=96 text=
verify cart s2 -> false (cart view=false title=false items=false footer=false)
```

- 点击后没有出现 cart.feature 节点；
- cart prelude 坐标仍未真正打开购物车；
- cart 导航仍未 PASS。

下一步必须在 Preview 中观察 720x1280 的实际 cart tab 命中位置，或使用可点击节点而不是固定坐标。

## 2026-10-19 JD cart 导航 PASS（release）

真机日志：

```text
verify s1 -> true
JD cart policy selected: s2 verifier=jd.cart
verify s2 -> true (matched=2, missing=0)
JD cart policy selected: s3 verifier=jd.cart
verify s3 -> true (matched=3, missing=0)
result: DONE · all subgoals verified actions=1 attempts=3
```

- 从 JD 首页通过 prelude 点击底部购物车 tab；
- s2/s3 由视觉 verifier 识别购物车页面/商品/数量/价格；
- 任务 DONE；
- 没有结算/支付动作；
- 购物车中商品：影视飓风“凉伴”Air 短袖，星夜黑 XL，数量 3，合计 ¥165.80。

结论：**JD 首页 → 购物车导航 PASS**（不包含商品详情/加购）。

## 2026-10-19 人工 JD 验证后：详情/加购可访问

用户完成“京东验证”后，JD 商品详情页恢复：

```text
ProductDetailActivity
nodes=136
text="加入购物车" id=com.jd.lib.productdetail.feature:id/alr
text="立即购买"   id=com.jd.lib.productdetail.feature:id/am_
```

人工链路验证：

1. 详情页点“加入购物车”；
2. 规格弹层选择 星夜黑 / XL / 数量1；
3. 点“确定”；
4. 打开购物车：

```text
购物车 (1)
影视飓风“凉伴”Air 短袖，星夜黑 XL
数量从 3 → 4
合计 ¥224.40
```

- 加购成功；
- 未结算/未支付；
- 中途误点“立即购买”进入结算弹层，立即 Back 退出，没有提交订单。

结论：**JD 详情页可访问、加购人工 PASS**；agent 自动化加购仍需把“加入购物车 + 规格确定”做成 prelude/policy。
