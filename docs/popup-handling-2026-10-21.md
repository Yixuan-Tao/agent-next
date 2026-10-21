# 弹窗检测与关闭：开源方案调研与实现记录

更新：2026-10-21。状态：**通用弹窗检测/关闭已实现并通过 host 回归与真机受控页验证；小米商城促销浮层因其无可访问的关闭控件仍无法自动关闭，真实购物流程因此仍未跑通。**

背景：真实小米商城流程测试中，Agent 每次进入搜索页都会遇到促销浮层；模型用 `Back/返回` 关闭会把整个搜索页退回首页，形成循环，流程无法到达商品/结算页。要求把弹窗检测与关闭做成通用能力，优先采用开源方案的做法。

## 调研到的开源方案

| 来源 | 做法 | 采用情况 |
|---|---|---|
| [openatx/uiautomator2](https://github.com/openatx/uiautomator2)（MIT） | `Watcher` / `WatchContext`：先检测特定文本/元素（可成对条件），命中后执行 `click()`；README 明确建议“点击元素前先检查弹窗”，并内置常见中文弹窗规则（如“立即下载/立即更新”+“取消”→点取消） | 采用“先检测再动作 + 成对条件”的思路；**不采用**其自动点击“同意/确定/好的”等正性按钮的规则 |
| [Android UiAutomator `UiWatcher`](https://developer.android.com/reference/androidx/test/uiautomator/UiWatcher)（AOSP 开源） | 在查找元素失败时运行注册的 watcher 处理弹窗/ANR/权限框 | 采用“可插拔 watcher 在策略链中优先拦截”的结构 |
| [Appium UIWatchers plugin](https://github.com/rajvinodh/uiwatchers)（MIT） | `referenceLocator`（触发条件）+ `actionLocator`（关闭动作）+ priority/cooldown/expiry/stopOnFound | 采用“触发条件 + 动作目标分离、冷却/重复上限、过期”的声明式设计；本项目为进程内策略，不需要 Appium 依赖 |
| 本仓库已有 `RiskGuardPolicy` | 验证/风控/付费页面信号先做 human handoff | 保持最高优先级，弹窗策略只处理**良性弹窗** |

未直接集成上述框架依赖（uiautomator2 是 Python + HTTP RPC，Appium plugin 需要 Appium server）；本项目把同样的**声明式 watcher 思路**落地到自己的语义策略层，保持纯 Java、无新增第三方依赖。

## 实现

- `brain/.../skill/PopupDetector.java`（纯 Java，可 host 测试）：
  - **触发条件**：可见、可点击、已启用的节点，文本/`contentDescription` 长度 ≤24；
  - **强关闭词**：关闭/关闭弹窗/关闭广告/跳过/跳过广告/close/skip/dismiss 等，出现即可关闭；
  - **弱否定词**：我知道了/以后再说/暂不/下次再说/取消 等，只有同屏存在**强促销 CTA**（立即下载/立即更新/立即领取/立即开通/下载/升级/领取/开通 等）时才关闭，避免把普通“取消”误判为弹窗关闭；
  - 多个候选取强词优先、同分取**面积更小**的节点（点 X 而不是包含关闭文字的大容器）；
  - **安全护栏**：出现支付/付款/提交订单/金额/验证码/密码/登录/授权/绑定/安全验证/人机验证/滑块验证/身份验证/captcha 等页面时**永不自动关闭**，交给 `RiskGuardPolicy` 做人工交接；不点击“同意/允许/确定/好的/继续”等正性按钮；不做坐标猜测。
- `app/.../skill/PopupGuardPolicy.java`：
  - 在策略链中位于 `RiskGuardPolicy` 之后、加载等待/模型之前：风险页 → 弹窗 → 加载等待 → 模型；
  - 命中后用已观察节点的中心点执行 `TapPointAction`（来源是 accessibility 节点，不是模型猜坐标）；
  - 同一弹窗签名最多尝试 2 次（cooldown/stopOnFound 思路），之后交还模型/加载逻辑，避免死循环；
  - 日志 `AgentNextPopup: popup dismissed: <label>` 供设备证据。
- Host 回归 `PopupDetectorTest`：促销弹窗关闭、强关闭优先、弱取消需 CTA 配对、不点正性按钮、支付/验证页不关闭、普通正文含“验证”不误判、空白/大容器边界。BrainHostTest 27 suites PASS。

## 真机验证（受控弹窗页，真实夸克）

本机受控页 `work/handoff-probe/popup.html`：正文 + 固定促销层“限时福利/立即领取新人礼包/关闭”。在真实夸克打开后，用中性任务（“在夸克查看当前页面的内容并告诉我这是什么页面”，未提弹窗/关闭）执行：

```text
03:05:20.861  verify s1 ... text=任务页面 | ... | 限时福利 | 立即领取新人礼包 | 关闭 | ...
03:05:20.864  I AgentNextPopup: popup dismissed: 关闭 node=...:7
03:05:20      后续观察已无“限时福利/关闭”节点，流程继续
```

弹窗由**策略层自动关闭**（先于模型动作），没消耗模型决策；关闭后页面正文仍可见。证据：`work/popup/evidence/popup-dismiss-2026-10-21.log`。

## 仍未解决：小米商城促销浮层

重试真实小米商城流程（同一任务文本“到小米商城买小米手环11，颜色没有要求”）：

- 搜索页加载完成后出现的促销浮层在 accessibility 树中**没有“关闭/跳过”控件**，只有“返回”；
- `PopupDetector` 正确地**不点“返回”**（它不是关闭控件，会把整个搜索页退出）；
- 模型仍会反复点搜索入口 → 浮层 → 返回，最终无法进入商品/结算页；本轮仍以 `NEED_USER` 结束。

因此弹窗能力本身已生效，但该 App 的浮层没有可访问的关闭语义，需要后续：优先等待浮层自行消失/可跳过、基于 `contentDescription`/视觉检测关闭 X、或为小米商城写受控 domain skill；**不能**用 Back 盲退。相关失败记录见[小米商城流程记录](shop-flow-attempt-2026-10-21.md)。

## 边界

- 只处理有可访问语义的良性弹窗；不可访问的自绘浮层（WebView/RN/系统弹窗）不保证。
- 不自动允许权限、不自动同意条款、不自动关闭支付/验证/凭据页面；这些仍走人工交接。
- 单设备 Android 16 验证；未覆盖多语言（当前关键词中英为主）、系统级 ANR/崩溃对话框。
- 不改用户账号/订单；受控页无真实支付。

## 证据与复现

- 代码：`brain/.../skill/PopupDetector.java`、`app/.../skill/PopupGuardPolicy.java`、`AgentRuntime` 策略链、`PopupDetectorTest`。
- 受控页：`work/handoff-probe/popup.html`；日志：`work/popup/evidence/popup-dismiss-2026-10-21.log`。
- 设备包 SHA-256：`f2f1f83a…52c5b5`（含本轮弹窗能力）。
- 复现：同签名安装 → 夸克打开受控弹窗页 → `MainActivity --es goal "在夸克查看当前页面..." --es primary com.quark.browser` → 生成计划/执行 → 观察 `AgentNextPopup` 日志与弹窗消失。
