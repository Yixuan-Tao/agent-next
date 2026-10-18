# Chain B attempt：JD 搜索 → 加购 → 购物车确认

日期：2026-10-19  
设备：Redmi Pad Pro，Android 16 / API 36  
状态：**部分完成**；JD 搜索 DONE，加购/购物车段被商品详情页无障碍结构阻塞

## 已完成：JD 搜索

目标：

```text
在京东搜索短袖T恤男款XL并看到搜索结果
```

计划 3 段：

```text
1. 打开京东首页，确认搜索入口
2. 搜索框输入关键词（不提交）
3. 提交搜索，停留在结果页
```

真机：

```text
verify s1 -> true (matched=2, missing=0)
verify s2 -> true (matched=2, missing=0)
verify s3 -> true (matched=3, missing=0)
DONE actions=2 attempts=3
```

![JD search DONE](assets/chain-b-search-done.png)

## 未完成：加购/购物车

目标：

```text
在京东搜索短袖T恤男款XL，把第一款加入购物车，打开购物车确认商品和XL规格，不要结算
```

计划 5 段，前两段通过；s3 打开搜索结果第一款商品详情后：

```text
verify s3 -> false (matched=0, missing=3) nodes=96 text=
skill step=0 -> Tap ...
skill step=1 -> Done
verify s3 -> false
```

JD 商品详情页对无障碍树几乎不暴露文本；模型只能反复 Tap 旧 selector/Wait，
无法可靠选择 XL/加入购物车。任务没有进入购物车，也没有结算/支付。

![Chain B 计划](assets/chain-b-plan.png)

## 关键改动

- JD 首页搜索入口在无障碍树缺失：
  - 子目标 intent 含“输入/提交”时，用一次 scoped top search entry（文本/宽 top clickable，最后 scoped 坐标）；
  - 不在“打开首页”子目标提前打开搜索页，避免 s1 错误。
- planner 现在忽略**未被使用的额外字段**，但仍严格校验必需字段/target/verifier/DAG，避免模型多写一个无害字段导致整次规划失败。

## 下一步

- 为 JD 商品详情增加 domain/wait/专用 verifier，或改成确定性的“搜索 → 第一款 → 规格 → 加购”路径；
- 购物车 verifier 应能读取商品标题 + XL，并断言没有结算按钮/未进入支付；
- 若详情页仍被风控/登录阻塞，要显式 NEED_USER/STOPPED，不能假装 DONE。

## JD 商品详情无障碍探测（2026-10-19）

对 s3 商品详情页做了完整 node dump（96 个节点）：

```text
clickable nodes: 2
  1. ScrollView  [0,0,720,1280]
  2. ScrollView  [0,186,720,1280]
text: 几乎全为空
viewId: 顶层容器若干，无“加入购物车/规格/XL”可点击节点
```

结论：

- 不是模型选错 selector，而是 JD 详情页把内容画在自定义 View 里；
- 通用 `DeviceApi` 只能 tap/swipe 坐标，无法通过语义 selector 找到规格/加购按钮；
- 继续让 prompt model 重试只会产生无效动作，必须做 scoped coordinate/domain/web 路径；
- 在拿到可靠详情页坐标/结构前，不应继续自动点击，避免误触“立即购买”。

## Risk/CAPTCHA safety guard（2026-10-19）

一次重复自动化后 JD 进入了“京东验证”页面。没有尝试绕过/识别验证码。

新增：

- `HumanHandoffRequiredException` + `FastLoop` 捕获：policy 检测到风险页时直接返回
  `NEED_USER`，不执行动作；
- `RiskGuardPolicy`：匹配“京东验证/安全验证/人机验证/验证码/滑块验证/CAPTCHA”等信号；
- 所有 app policy（prompt、Quark、Calendar）都包在 guard 外；
- host test：风险信号 → `NEED_USER actions=0`。

在拿到可靠的 JD 详情页坐标/domain 路径且账户不触发风控前，Chain B 不应继续自动点击。

## JD cart-tab probe（失败）

尝试用 scoped 底栏坐标打开购物车：

```text
JD home tab selected
verify s1 -> false ...
FAILED · observe failed: UNAVAILABLE
```

- home tab 后 JD 页面进入不可观察/不可用状态；
- 没有到购物车，没有结算；
- 说明 JD 在当前账户/风控下连“打开购物车”都不稳定，继续盲试没有价值。

结论：Chain B 的剩余部分需要**人工接管/主屏 handoff**，或换一个未被风控的 JD 账户/设备；在风控解除前不应继续自动点击。
