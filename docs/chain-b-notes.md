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

## 2026-10-19 自动化加购 policy（已编译，未验证）

新增：

- `TapPointAction`：按 accessibility node bounds 点击；
- `JdAddPolicy`：详情页只点“加入购物车”，规格弹层只点“确定”；
  `complete()` 在确认点击后返回 true；绝不点“立即购买/结算”。

已编译并安装新 release。尚未跑完整 planner→detail→add 任务，
不能宣称自动化加购 PASS。

## 2026-10-19 自动化加购尝试

任务：搜索短袖 → 打开第一款 → 加入购物车。

发现并修复：

- cart 目标预打开逻辑误匹配“加入购物车”，导致 s1 先打开购物车；
  已排除 `加入购物车`；
- JD 搜索 prelude 只在 intent 含“输入/提交”时触发，planner 的“搜索”措辞未触发；
  已扩展到包含“搜索”。

本轮任务仍停在 s2 搜索页（模型 SetText/Tap 未成功），
因此 **自动化加购仍未 PASS**。人工加购已 PASS（数量 3→4）。

## 2026-10-19 确定性搜索 policy 尝试

新增 `JdSearchPolicy` + `SetTextNodeAction`：

- 在可编辑节点设置 query；
- 优先点搜索按钮，否则点 720×1280 顶部 `(644,60)`；
- `complete()` 在提交后为 true。

同时：

- cart 目标预打开排除“加入购物车”；
- 搜索 policy 排除“打开/首页/启动”子目标。

真机任务仍在 s2 搜索页，模型 SetText/Tap 与 policy 混用，
没有进入详情/加购；自动化加购仍未 PASS。

## 2026-10-19 规划器要求独立加购子目标

planner prompt 增加：目标含“加入购物车”时必须单独生成加购子目标。
真机计划已出现 3 个 subgoal：

1. 搜索并查看结果；
2. 打开第一款详情；
3. 加入购物车 (jd.cart)。

但执行仍在 s1 搜索阶段反复 verify false；最后一次 UI 回到“尚未生成计划”，
没有进入详情/加购。自动化加购仍未 PASS。

## 2026-10-19 搜索 s1/s2 已 PASS，s3 详情仍失败

JdSearchPolicy 改进：

- 无 editable 时先点搜索入口 (360,100)；
- 提交固定点顶部 (644,60)，避免误点输入框；
- 记录 typed/submitted/editable/search 状态。

真机：

```text
verify s1 -> true
verify s2 -> true
JD policy candidate s3 intent=打开搜索结果中的第一款商品详情页
verify s3 -> false ... nodes=96 search.feature
```

- 搜索输入/提交已经 PASS；
- s3 打开第一款详情仍未 PASS；
- 没有进入加购 s4；自动化加购仍未完成。

## 2026-10-19 result-card policy + 广告阻塞

新增 `JdResultPolicy`：优先点击结果页含 ¥/￥ 的节点，否则点首卡区域 `(180,360)`。
但本轮 s2 在执行搜索前，JD 首页出现“挑好物逛京东”广告/Babel 浮层：

```text
JD search policy typed=false ... editable=false search=true
verify s2 -> false ... 挑好物逛京东
```

搜索入口点击被广告拦截，未进入搜索结果；自动化加购仍未 PASS。

## 2026-10-19 广告检测/重试搜索入口

- `prepareJdHome` 检测“挑好物逛京东”并 Back；
- `JdSearchPolicy` 对无 editable 最多 3 次点击搜索入口 (360,100/90/120)，检测广告后 Back。

真机仍在 s2 遇到“挑好物逛京东”广告，未进入搜索；
自动化加购未 PASS。

## 2026-10-19 s3 结果卡误点地址页

`s1/s2` 已 PASS；`JdResultPolicy` 多点击价格节点/首卡后，
s3 打开的是“选择地址 / address/delivery”，不是商品详情：

```text
verify s3 -> false ... 选择地址 id=/address/delivery?stamp=1
```

- 误点进入地址选择页；
- 未进入商品详情/加购；
- 已 force-stop JD/app，未下单/未支付。

结论：自动化加购仍未 PASS；结果卡点击需要更精确的节点/坐标，
不能再用盲点击。

## 2026-10-19 去除 JD 硬编码坐标 + 视觉定位

用户要求：JD 自动加购不得写死坐标，必须读控件或使用识别模型。

已做：

- 删除 JD 搜索入口/提交/结果卡/购物车/ad 的固定坐标；
- 新增 `JdVisualLocator`：用视觉模型返回归一化点，只接受 confidence≥0.5；
- 新增 `VisualTapAction`：执行时抓 fresh frame，视觉定位后点击；
- `JdResultPolicy` 只接收视觉模型给出的商品卡片点；
- 搜索入口/提交优先读控件，无控件时用视觉定位；
- JD 首页/购物车 tab 只按可点击节点操作，不再盲点。

最新真机仍 STOPPED 在 s2 搜索提交，未进入结果/详情/加购。
人工加购仍 PASS。自动化加购未完成。

## 2026-10-19 视觉提交按钮

`JdSearchPolicy` 提交阶段改为始终调用 `VisualTapAction`：

```text
visual tap point=Point{639,79}
target=京东搜索页右上角的搜索提交按钮
```

说明视觉定位可用，但 s2 verifier 仍 false，任务 STOPPED；
自动化加购仍未 PASS。无硬编码坐标。

## 2026-10-19 互联网资料与 deeplink 实测

参考 JD 官方文档：
- JSSDK `toSku(skuId)` 跳商品详情
  https://opendoc.jd.com/jd-jssdk/v4/jm-redirect.html
- 第三方示例给出 openApp 协议：
  `openApp.jdMobile://virtual?params={"category":"jump","des":"productDetail","skuId":"..."}`

实测（SKU 10215092347246）：

```text
am start -d openApp.jdMobile://virtual?params=...
topResumedActivity=com.jingdong.app.mall/.bundle.jdrhsdk.JDRiskHandleActivity
```

直接 deep link 进入了 **京东验证**，不是商品详情。
说明：JD 对直接 deeplink/自动化访问触发风控；不能绕过，也不能保证全自动加购。
JD 搜索结果页的控件树也不包含商品卡（仅 header/filter/地址），所以视觉/控件
都无法稳定拿到首卡。

结论：JD 自动加购的可靠方案是 **人工完成验证 + 人工/半自动加购**；
纯自动路径在风控存在时不可保证。

## 2026-10-19 候选列表 + 安全过滤

- `VisualCandidate` / `VisualCandidateFilter`（brain，host test 18 suites）；
- `JdVisualLocator` 改为返回最多 8 个候选，按 allow/forbid/confidence 过滤；
- 搜索提交允许“搜索/提交/按钮”，拒绝“拍照/大图/显示模式/筛选”；
- 结果卡允许“商品/商品卡/卡片”，拒绝“广告/地址/筛选/结算”；
- 视觉输出 token 从 900 提高到 4096，修复 `finish_reason=length`。

真机：
- 视觉提交曾成功：point 666,69 → s2 true；
- s3 视觉定位曾因 token length 失败，已提高预算；
- 最新重跑仍 STOPPED，未完成详情/加购；自动加购仍未 PASS。

## 2026-10-19 cart 修复与 add 诊断

- JD cart tab 视觉候选增加 debug 日志、底部区域过滤、4 次轮询；
- target prepare 不再预先打开购物车；
- 真机购物车任务：DONE · all subgoals verified actions=0 attempts=3；
- 自动加购：s2 搜索提交视觉候选正确（搜索按钮红底 x=.91 y=.07），点击后仍停在搜索页，s2 false；
- 未完成详情/加购，自动加购仍未 PASS。

## s2 提交后继续验证

`SearchPolicy.complete` 改为 false，搜索提交后持续 Wait 2.5s，让 verifier 观察结果页；
避免“已提交即完成”导致 s2 过早失败。真机仍 s2 false，搜索页未进入结果页。

## 2026-10-19 s3 详情与视觉加购

- SearchPolicy 增加搜索控件诊断：搜索节点 `text=搜索 bounds=619..691,64..106`；
- 搜索提交后 7s 曾 s2 true，但重跑仍会停在搜索页（flaky）；
- s3 结果卡点击曾进入 `productdetail.feature` 页面；
- JD verifier 对详情页增加 `productdetail` view id 证据；
- AddToCartPolicy 增加视觉“加入购物车/确定”fallback，禁止立即购买/结算；
- 最近一次加购计划在 s2 停止，s3/s4 未执行；自动加购仍未 PASS。

## s1/s2 深挖

- `SearchPolicy` 发现一个 bug：`submit` 控件已找到但未使用，始终走 VisualTapAction；
- 已改为优先 TapPointAction(submit node)，搜索按钮 bounds=619..691,64..106；
- 搜索提交后等待 7000ms；
- JD verifier 对“搜索页 search.feature editable”放宽为 s1 证据；
- 但计划 s1/s2 文案变化会导致 s1 在首页/搜索页之间反复失败，自动加购仍未稳定。

## 固定 JD 加购计划

新增 `JdAddPlan`：当 goal 含“京东 + 加入购物车”时替换规划器 subgoals：

1. 打开京东首页（jd.home）
2. 搜索（visual）
3. 打开第一款详情（jd.detail）
4. 加入购物车（visual）

`DomainSkill.fixedPlan` + `DomainSkills.fixedPlan`，AgentRuntime 在计划确认前替换。
真机：s1 不再漂移；s2 仍会在搜索提交后停止，s3/s4 未执行。

## 2026-10-20 Android 13 闪退修复

Pixel 4 XL / Android 13 首次启动 AgentNext 闪退：

```text
MainActivity.append
 <- MainActivity.addIfInstalled
 <- MainActivity.loadInstalledApps
 <- MainActivity.buildUi
```

原因：`loadInstalledApps()` 在 `log` TextView 初始化之前调用；JD/Quark/Calendar
都未安装时 `addIfInstalled` 调用 `append`，`log` 为 null。

修复：`append` 在 `log == null` 时只写 Logcat，不崩溃。
真机验证：Pixel 4 XL 启动成功，主界面显示“没有可用的 App”。

## 2026-10-20 Android 13 全链路准备

- 修复 fresh install 无 App 时 MainActivity 闪退（append 在 log 初始化前被调用）；
- 支持 Google Calendar（com.google.android.calendar）作为 calendar domain target；
- 支持 Gemini（com.google.android.apps.bard）作为可规划 App；
- CalendarStore 支持“周五下午7.30”解析：下一个周五 19:30，1 小时，标题“准备 Agent 开发面试”；
- 真机 Pixel 4 XL：AgentNext 可启动；设备锁屏，尚未执行自然语言任务。
