# AppAgent 与 AgentNext 实现方式对比

日期：2026-10-21。对象：

- **AppAgent**：<https://github.com/TencentQQGYLab/AppAgent>，`main` 分支（源码快照 `2c19004`；`scripts/` 最后代码提交 2024-02-08），CHI 2025，arXiv 2312.13771。
- **AgentNext**：本仓库工作区（`codex/submission-sprint`，HEAD `b242ee6` 之上含未提交改动），2026-10-21 快照。

方法：两边都做了源码级阅读。AppAgent 侧读完 11 个 Python 文件（共 1,666 行，含 `learn.py`/`run.py`/`scripts/*.py`/`config.yaml`/`assets/testset.md`）；AgentNext 侧读完 4 个模块的主源码核心类（`app` 43 文件/4,735 行、`brain` 143/8,078、`capability` 46/5,728、`device-api` 27/1,220）及各 README、docs。本文比较的是**实现方式与工程取舍**，不评价论文价值，也不把任一方的规划文档当成已完成能力。

## 0. 一句话结论

**AppAgent 实现的是"让模型独占手机把任务做完、并把做过的元素经验写成文档"；AgentNext 实现的是"用户继续用手机时，agent 在私有屏把任务做完，且每一步可治理、结果可核验"。** 前者把工程自由度花在感知与知识沉淀上，后者把工程自由度花在隔离、治理与验证上。两者共享"观察 → 模型决策 → 动作"的骨架，但在**执行位置、手机所有权、观测—动作桥、决策结构、知识来源、完成判定**六处是结构性不同，不是同一架构的两种参数配置。

## 1. 对照总表

| 维度 | AppAgent | AgentNext |
|---|---|---|
| 项目定位 | 研究框架 + 公开 benchmark，证明 MLLM 可操作手机 | 面向"同机并行、不打扰、可验证"的产品原型（题目六条硬性要求） |
| 运行位置 | **PC 端 Python + adb**，手机只是截图/注入端点 | **端侧 Android app** + Shizuku user service（uid 2000 的 `agent_next_shell` 进程）；brain 为纯 JVM 库，跑在 app 进程 |
| 手机前台模型 | agent 独占当前前台，用户无法同时使用 | 用户主屏不动；agent 在**私有虚拟屏（1080×1920，360 dpi）**启动/移入目标 App，输入定向注入该 display |
| 权限依赖 | USB/无线调试 + PC 常驻；无需 root | Shizuku user service + 隐藏 API（`DisplayManager.createVirtualDisplay`、`setLaunchDisplayId`、AudioPolicy 反射）；无需 root，但需一次无线调试授权，平台兼容面窄 |
| 观测 | 整屏 `screencap` + `uiautomator dump`；抽 clickable/focusable；截图上打**数字编号**；`grid()` 兜底 | 目标 display 窗口内节点树（obsId/sequence/focusedWindowId/bounds/≤96 节点 text·desc·viewId·flags）+ 单帧 PNG；旧观察/旧句柄/主屏输入一律拒绝 |
| 模型输入 | 带编号**截图** + 元素文档文本 + 任务描述 + 上一动作摘要 | 结构化**节点文本列表** + 可选当帧截图 + 计划/阶段/完成条件/上次执行反馈 |
| 模型输出 | 自由文本 `Observation/Thought/Action/Summary`，正则解析；`tap(编号)/text/long_press/swipe/grid/FINISH` | 严格 JSON `{kind: Tap/SetText/Back/Wait/Done, selector, risk}`，多余字段直接拒绝；**只能给语义 selector，不能给坐标** |
| 定位方式 | 编号 → 当轮 `elem_list[编号-1].bbox` 中心 → adb 坐标；grid 区域 → `area_to_xy` | selector → `ActionGrounder` 在**最新观察**上唯一命中（0 匹配 STALE、多匹配 REJECTED）→ 执行前重新观察+重新 grounding+payloadHash 比对 |
| 动作空间 | tap / text / long_press / swipe / grid；无 back/启动/滚动原语 | Tap / SetText / Back / Wait（内部另有受信任层生成的 Scroll、弹窗关闭点位）；无 long_press/拖拽/网格 |
| 规划 | **无 planner**：任务描述 + 知识库文档，逐步即席决策 | `TaskPlan`（1–8 子目标 DAG + verifierId + postconditions）+ `TaskContract` + `PlanValidator` + 计划审阅 UI；浏览器任务另有窄口径 normalise |
| 执行循环 | 单层循环：截图 → 一次模型请求 → 一个动作 → 固定 10s 间隔；`MAX_ROUNDS=20`；跨轮记忆只有 `last_act` 一句摘要 | `SessionRunner → SubgoalRunner → FastLoop`：observe→verifier→policy→RiskGate→ActionExecutor→EffectEvaluator→SlowLoop；预算 40 动作/10 分钟/noEffect 4/noProgress 5；暂停/停止检查点 |
| 反思/纠错 | 仅**探索阶段**有 4 决策反思（BACK/INEFFECTIVE/CONTINUE/SUCCESS）；部署阶段无 | 效果评估（LOADING/NO_CHANGE/CHANGED/NAVIGATED）+ STALE/REJECTED 3 次重试 + Done 后 2 次有界滚动 + 慢循环（**产品注入的策略恒为 AskUser，自主重规划未启用**） |
| 知识/学习 | **核心贡献**：自主探索或人工演示 → 每元素功能文档（`apps/<app>/auto_docs|demo_docs/<resource-id>.txt`，5 个 key）→ 部署时按当前屏幕 resource-id 精确注入 `<ui_document>` | 仅 3 个手写 domain skill（Quark/Files/Calculator）+ 动态 prompt skill；`SkillRegistry`/信任门/候选验证代码存在但产品路径不用；Semantic memory 未落地，跨任务不沉淀知识 |
| 完成判定 | 模型输出 `Action: FINISH` 即打印成功；**无 verifier、无终态断言** | 独立 `SubgoalVerifier`（视觉 verifier + domain verifier）判定子目标；浏览器任务做同帧最终核验；`TaskReport` 再兜 DONE 门；模型 Done 不算成功 |
| 风险/人类介入 | 无风险分级、无确认、无接管；凭据/支付页面照点 | A0–A3 自主级别 + `DefaultRiskGate`/`GuiRiskClassifier`（按实际命中节点文字抬风险）+ 一次性确认 token（绑定任务合同/target 版本/窗口/节点指纹）+ 凭据硬 handoff + 支付/验证页自动非抢屏交接 + 预览触摸接管 |
| 隔离与"不打扰" | 整屏截图与输入作用于用户当前前台；无后台/静音/通知机制 | 私有 display + IME policy HIDE + 按目标 UID 音频静音（仅用户接管恢复）+ preview 镜像/只读监看 + 交接通知 |
| 记忆 | `last_act` + 每 App 文档文件；无跨 run 状态 | Working/Episodic/Semantic/User 设计；加密 Episodic 已实现；`StageData` 少量键值跨子目标传递 |
| 模型/成本 | GPT-4V 每步 1 请求（约 $0.03/请求），`REQUEST_INTERVAL=10s`；Qwen-VL 备选；单模型单轮 | 任意 chat-completions 兼容（DeepSeek 等）；planner/actor/verifier/extractor 多角色分离；单任务模型调用预算 80 次；30s 超时、1 MiB 上限 |
| 工程形态 | 11 个 Python 文件/1,666 行；无测试、无 CI、无状态模型 | 4 个 Gradle 模块、259 个主源文件约 1.98 万行、54 个测试文件；AIDL 协议版本（v4/user-service 14）、host 测试 + 设备 smoke + 按日期证据记录 |
| 评测 | 45 任务/9 App 公开 testset（论文口径 50 任务/10 App），离线人工判分；论文 Table 1 报告知识来源对成功率的影响 | **无冻结任务集/端到端成功率**；24+12 任务集仍在计划中；证据集中在单主题 Quark 三次 DONE、受控交接页；Android 13、真实支付/登录/购物未验收；演示视频未完成 |
| 泛化面 | 视觉+树标号，可操作无文本/自绘控件（grid 兜底），但强依赖 dump 与 resource-id 稳定性 | 以可访问性语义为主，文本型原生界面更稳；RN/WebView/自绘控件弱（多次失败记录），视觉 locator 代码未注册 |

## 2. 五处结构性差异的机制细节

### 2.1 执行主体：PC 远程控制 vs 端侧特权服务

AppAgent 的全部智能与执行都在 PC：`and_controller.AndroidController` 用 `adb -s ... shell screencap`、`uiautomator dump` 取数据和 XML，用 `input tap/swipe/text/keyevent` 注入动作；`task_executor.py` 每轮一次模型请求、一个动作，`time.sleep(REQUEST_INTERVAL)`。手机侧没有任何常驻代码，也没有 App 启动能力（要求人先把 App 摆到主界面）。这意味着：PC 掉线、拔线、手机锁屏都会中止任务，且**不存在"用户在用手机"这个状态**。

AgentNext 反过来：`AgentRuntime`（app 进程）持有 brain，通过 `BlockingDeviceApi → ConnectionCoordinator → ShizukuBackend → IAgentShell` 调用 uid 2000 的 user service。display、target、observer、input、audio、mirror 全部在特权 shell 进程里创建并持有；app 进程只是客户端。作用域因此是"某个 display 上的某个 target/窗口"，而不是"整台手机"。

### 2.2 所有权与隔离：抢占前台 vs 私有屏并行

这是两边最根本的差异，也是"能否满足同机并行/不打扰"的分水岭：

- AppAgent：截图是整屏、输入是当前前台，agent 与用户只能**分时共用**手机。它的运行前提就是"人别碰手机"。没有虚拟屏、没有静音、没有暂停/让行、没有与用户操作互斥的抽象。
- AgentNext：私有 display 强制 IME HIDE；目标 App 用 `ActivityOptions.setLaunchDisplayId` 启动到私有屏；输入由服务端按 target+窗口+UID 定向注入，一个 observation 只授权一次动作，旧观察/旧句柄/主屏输入被显式拒绝；主屏出现同 App 即判 `FOREGROUND_CONFLICT` 并交还声音；音频按目标 UID 路由到丢弃 sink，仅用户主动接管时恢复，交还前先确认重新静音；用户可通过 preview 镜像触摸接管，或从通知进入受控接管。

代价也很直接：AppAgent 只要 adb 就能跑在任意 Android 手机/模拟器上；AgentNext 被 Shizuku、隐藏虚显 API、task move、隐藏 AudioPolicy 和 ROM 行为绑定，目前只有单设备/单 ROM（Redmi Pad Pro，Android 16）的完整证据。

### 2.3 观测—动作桥：编号→bbox vs 语义 selector→重定位

两边的共同判断是"**不能让模型直接输出 xy 坐标**"，但桥不一样：

- AppAgent：用 uiautomator XML 的 clickable/focusable 节点，在截图上画**数字徽标**（`draw_bbox_multi` 实际只画数字、不画框），模型只回编号，PC 用当轮 `elem_list[编号-1].bbox` 中心回代。论文 Table 1 的对照（无文档时 raw 坐标 SR 2.2% vs 简化动作空间 48.9%）正是这条路线的依据。它的优点是：视觉模型看到编号与原图的空间对应，自绘/无文本控件也能选；缺点是编号是**一次性指针**——截图后界面变了，编号仍会被回代到旧 bbox，且 dump 失败时连 grid 都走不到。
- AgentNext：模型看到节点文本（text/desc/viewId/class/flags）与当帧截图，输出语义 selector（`TextContains`/`ViewIdContains`/`ClickableTextContains`/`ClickableContentDescriptionContains`/`Editable`/`WebTextContains`），`ActionGrounder` 在最新观察上解析：0 匹配 `STALE`、多匹配 `REJECTED`，不猜；`ActionExecutor` 执行前再观察、再 grounding、比对 payloadHash（含节点 text/desc/viewId/bounds/flags）后才落点。优点是抗界面漂移、可审计、无坐标幻觉；缺点是依赖可访问性语义——WebView/RN/自绘控件多次失败，`WebTextContains` 的条件又很严，产品里也没有可用的视觉兜底（旧 JD visual locator 未注册）。

一句话：**AppAgent 用"视觉—编号"桥换通用性，AgentNext 用"语义—重定位"桥换可控性与可验证性。** 这两条桥可以并存：把编号只作为视觉提示、由受信任层把编号映射回节点 ref，就能在"模型不给坐标"的前提下获得 AppAgent 的视觉接地能力。

### 2.4 决策结构：单步反应式 vs 计划 + 快慢双循环

- AppAgent：**没有规划层**。每次请求把任务描述、上一动作摘要、当前屏元素文档塞进 prompt，模型即席决定下一步；没有子目标、没有完成条件、没有预算（只有 `MAX_ROUNDS`）、没有重试与重规划，唯一跨轮状态是 `last_act` 的一句 Summary。它把"会不会办事"整体压在模型与知识库上。
- AgentNext：先规划（严格 JSON，1–8 子目标、依赖、目标 App、verifierId、1–3 条可见 postconditions），经 `TaskContract`/`PlanValidator` 校验并让用户审阅；执行时按子目标串行切 App，每个子目标跑 FastLoop（观察稳定性、verifier 完成判定、风险门、唯一执行器、效果评估、no-effect/no-progress 计数），升级到 SlowLoop 决定继续/替换/插入/移除/问人/停止。模型只是策略层的一个实现，完成与否不由模型自评。

同样要如实说：AgentNext 的产品路径把 SlowLoop 注入成 `AskUser`，`BoundedReplanPolicy` 只在测试/debug；跨子目标的 `StageData` 还是少量字符串键值。所以"有规划 + 有恢复"目前是**结构上有、产品路径上弱**。

### 2.5 完成判定与风险治理：模型自评 vs 证据式完成

- AppAgent：部署循环里模型输出 `Action: FINISH` 就打印 "Task completed successfully"，没有终态截图断言、没有独立 verifier、没有风险门。它的成功率来自论文的离线人工判分（10 步内完成 + 人工 reward），不是运行时可验证的结论。支付、发送、删除、凭据输入在动作空间里没有任何特殊处理。
- AgentNext：子目标完成由 `SubgoalVerifier`（视觉 verifier；Quark/Files/Calculator 另有 domain verifier）判定；视觉 verifier 校验"每个 postcondition 都被 matched 覆盖且 missing 为空"；浏览器任务额外做同帧标题/主题/正文核验；最后 `TaskReport` 再兜一层 DONE 门。模型输出 Done 只会让策略返回空动作，进而 2 次有界滚动后升级为 NEED_USER，**不会直接产生成功**。动作侧：模型自报 LOW 会被 `GuiRiskClassifier` 按实际命中节点文字抬升；承诺动作要一次性确认（token 绑定合同/target 版本/节点指纹，执行前重新 grounding，内容变化即失效）；密码字段在 grounding 层直接被拒；支付/验证页面触发非抢屏交接。

这一项的差距不是"实现细节"，而是**验收口径**：AppAgent 的"完成"是模型声明，AgentNext 的"完成"是证据链。题目中"结果可验证"这一条，AppAgent 架构上无法满足。

### 2.6 知识来源：两阶段元素文档 vs 手写 skill（当前最大能力缺口）

AppAgent 的论文贡献有一半在"学习"：探索阶段（自主探索或人工演示）以任务为导向操作 App，用 before/after 截图让模型写**元素功能描述**，按 `resource-id` 存成文本文件；部署阶段按当前屏幕可见元素的 resource-id 精确查文件、拼成 `<ui_document>` 注入 prompt。它没有 schema、没有版本/置信度、没有信任门，App 升级导致 resource-id 变化就失配；但它是**可积累、可复用、可人工修订**的 app 知识。

AgentNext 的知识层是另一种设计：`SkillPackage` 有 contract/body/permissions/applicability/verifier/trust/provenance，`SkillRegistry` 有验证与信任门，`PromptSkillPolicy` 能把过程知识注入模型——但产品只注册了 3 个手写 domain skill，判定逻辑是流程化的；`Semantic memory` 只有设计（D4/D9），没有自动产线。结果是：**AppAgent 每跑一个新 App 都会变强一点；AgentNext 每次任务结束什么都不留下**（Episodic 加密存储只做审计/隐私，不参与决策）。这也是 `docs/pure-gui-beta.md` 里"通用性靠无专用 skill 保留集验收"最吃力的地方。

## 3. 相同点（避免把差异误读成全面优劣）

1. 都是"屏幕观察 → 模型决策 → 设备动作"的闭环，都不是固定点击回放。
2. 都刻意不让模型直接输出像素坐标，都用界面树辅助接地（AppAgent 的编号来自 uiautomator 树；AgentNext 用自有观察树）。
3. 都是单帧截图驱动，都没有多帧/视频输入；都以真实第三方 App 为操作对象。
4. 都走非 root 路径：AppAgent 用 adb，AgentNext 用 Shizuku（普通开发者可得）。
5. 都受同一个现实约束：App UI 变化会让定位失效；差别在于失效时是"回代旧编号继续点"还是"STALE 后重观察/交人"。

## 4. 两种约束下的最优解，而不是"谁更先进"

| 目标 | AppAgent 的解法与代价 | AgentNext 的解法与代价 |
|---|---|---|
| 通用设备、低门槛 | adb 即可，模拟器也行；代价是必须独占手机、PC 常驻 | Shizuku + 隐藏 API；代价是平台/ROM 兼容面窄、安装门槛高 |
| 同机并行、不打扰 | 架构上不支持（整屏观察+前台注入） | 私有 display + 定向输入 + UID 静音；代价是设备层复杂度与兼容风险 |
| 会办事、可积累 | 两阶段元素文档，长尾 App 也能沉淀；代价是无 schema/版本/信任，部署无纠错 | 手写 skill + 计划/验证/治理；代价是知识不可积累、长尾靠模型即席发挥 |
| 结果可验证 | 不支持（FINISH 即完成，评测离线人工） | verifier + TaskReport + 同帧核验；代价是模型调用更多、时延更高、verifier 本身也可能误判 |
| 成本/时延 | 每步 1 次请求、10s 间隔、约 $0.03/请求 | 规划+执行+核验多次调用，预算封顶 80 次；单帧截图与节点列表控制 token |

按题目六条硬性要求看：AppAgent 能满足"是 Agent""操作真实第三方 App""设备权限可说明"（但需要 PC）；对"同时用手机""不打扰""结果可验证"三条是**架构级不满足**。AgentNext 的架构正是为后三条设计的，代价是前三条里"操作真实第三方 App"目前只有夸克单主题的重复证据。

## 5. AgentNext 可以借鉴什么（按收益/成本排序）

1. **元素级功能文档 → Semantic memory 的产线**（收益最高）。离线跑人工演示或自主探索，把"这个控件做什么"写成带 App/版本/前置条件/verifier 的候选知识，经 D9 的验证与信任门进入 skill/semantic memory，而不是手写 domain skill。AppAgent 证明了这类知识能把无文档成功率从 48.9% 拉到 84.4%（人工演示，论文 Table 1），这对 AgentNext 的长尾泛化是最直接的补强。落点：`SkillRegistry`/`SkillCandidate`/`SkillProvenance` + `docs/decisions.md` D4/D9。
2. **截图编号作为视觉接地通道**（中等成本）。编号只作模型可见提示，由受信任层把 label 映射回 node ref 并校验窗口/UID；模型仍不能输出坐标。可补 WebView/RN/自绘控件的语义盲区，且不破坏 `ActionGrounder`/`ActionExecutor` 的治理链。落点：`VisualCandidate`/`VisualCandidateFilter`（已有类型未接线）+ `ActionGrounder` 增加 label→ref 解析。
3. **反思决策离线化**（中等成本）。AppAgent 的 BACK/INEFFECTIVE/CONTINUE/SUCCESS 与 useless-list 黑名单，可改造成"离线生成知识 + 人工抽检"的质量过滤器；不要引入运行期自主反思（会绕过风险门）。
4. **区域/网格定位的受控兜底**（低成本）。让模型选"区域+子区域"而不是像素，由受信任层换算成坐标并做命中校验；比"无法定位就失败"可用，比"模型给坐标"安全。注意与"坐标只存在于受信任层"（D9）保持一致。
5. **评测口径**（低成本但需要投入）。借用 AppAgent 的公开 testset 思路（跨 App、10 步口径、外部 oracle 判分），把 24+12 冻结任务集真正建起来；AppAgent 的教训是"模型自评 ≠ 成功率"，AgentNext 的 `TaskReport` 也要用独立 oracle 校验，而不是只看 DONE 日志。

## 6. 不建议照搬的部分

1. **PC+ADB 前台控制 loop**：与"同机并行/不打扰"直接冲突，不能作为产品执行路径。
2. **`FINISH` 即完成、无独立 verifier**：与题目"结果可验证"冲突，在真实支付/发送场景不可接受。
3. **明文 Python dict 文档 + 正则解析 + 无 schema**：与 D4 加密/保留期、D9 信任门、严格 JSON 契约都不兼容，只能借用"知识形态"，不能借用"存储与解析方式"。
4. **全局 `input text`/无字段绑定**：与 D7（target-bound setText）和 D3（凭据全人工）冲突。
5. **把长任务交给单步反应式决策**：AgentNext 已有 TaskGraph/verifier/TaskReport，回退到单步即席决策会丢掉可验证性。

## 7. 自检：别把 AgentNext 的规划当成已实现

对比时容易高估 AgentNext 的"治理/验证"成熟度。就代码现状而言：

- 产品 SlowLoop 恒为 AskUser，`LoopRunner`/`BoundedReplanPolicy` 只在测试/debug；`VerificationResult` 三态只存在于 docs。
- `stable` 目前等于 READY，没有 docs 要求的 250ms 双采样；`activityName` 恒空导致 NAVIGATED 分支失效。
- TaskReport 的覆盖判断是计划文本包含；浏览器最终核验只覆盖一条窄中文只读任务。
- domain skill 只有 3 个，JD legacy 未注册；无 checkpoint/进程恢复；确认卡缺金额/收件人等 D2 字段。
- 评测尚未冻结，Android 13、真实支付/登录、多 App 长任务没有设备证据。

这些缺口决定了两边对比的正确结论：**AgentNext 在"并行隔离 + 动作治理 + 证据式完成"上是结构领先，在"知识积累 + 视觉接地 + 评测规模 + 平台覆盖"上落后于 AppAgent 路线**；两边都还没有解决"任意 App 任意任务可靠完成"。

## 8. 证据索引

| 事实 | AppAgent 侧 | AgentNext 侧 |
|---|---|---|
| PC+adb 拓扑、整屏截图/XML | `scripts/and_controller.py:19-180` | — |
| 编号标号、坐标回代 | `scripts/utils.py:30-60`、`scripts/task_executor.py:172-203,226-231` | — |
| 网格兜底 | `scripts/utils.py:63-95`、`scripts/task_executor.py:117-139,149-152` | — |
| 自由文本动作解析、FINISH | `scripts/model.py:100-211`、`scripts/prompts.py:43-91` | — |
| 两阶段元素文档 | `scripts/self_explorer.py:90-265`、`scripts/document_generation.py:60-134`、`scripts/task_executor.py:175-203` | — |
| 无 verifier/无风险门 | `scripts/task_executor.py:207-284` | — |
| 私有 display / 定向输入 | — | `capability/.../AndroidDisplayResources.java:88-111`、`AndroidInputDispatcher.java`、`TargetLifecycle.java` |
| 观察契约/新鲜度 | — | `device-api/.../Observation.java`、`UiNode.java`；`capability/.../AndroidTargetObserver.java` |
| 音频隔离/交接 | — | `AndroidTargetAudio.java`、`TargetAudioIsolation.java`、`app/.../AgentRuntime.java` handoff 路径 |
| 规划/契约/校验 | — | `brain/.../plan/DeepSeekTaskPlanner.java`、`contract/PlanValidator.java`、`app/.../AgentRuntime.java:529-536` |
| 快慢循环/预算 | — | `brain/.../loop/FastLoop.java:105-336`、`session/SessionRunner.java:91-208`、`Budget.java:23-27` |
| 语义动作/重定位/确认绑定 | — | `brain/.../action/ActionGrounder.java:27-131`、`ActionExecutor.java:58-140`、`ConfirmationToken.java`、`GuiRiskClassifier.java` |
| 完成判定/同帧核验 | — | `brain/.../session/SubgoalVerifier.java`、`VisualSubgoalVerifier.java`、`app/.../BrowserResultVerifier.java`、`contract/TaskReportBuilder.java` |
| 知识/skill/记忆 | AppAgent：`apps/<app>/auto_docs|demo_docs/*.txt` | `brain/.../skill/*`、`app/.../skill/DomainSkills.java`、`app/.../security/EncryptedEpisodicMemory.java` |
| 评测 | `assets/testset.md`；论文 arXiv 2312.13771 | `docs/pure-gui-beta.md`、`docs/submission-demo.md`、`docs/*-2026-10-*.md` |

补充：AppAgent 的完整源码级笔记见 `work/compare/appagent-notes.md`（`work/` 被 gitignore，属本地分析产物）；论文原文数字取自 arXiv 2312.13771（摘要 50 任务/10 App；主表 45 任务/9 App；Table 1 为 raw 坐标 2.2%、简化动作无文档 48.9%、自动探索文档 73.3%、人工演示文档 84.4%、手工文档 95.6%；SR 口径为 10 步内完成）。

## 9. 结论

**AppAgent 是"以模型与知识为中心"的手机 Agent 研究框架：感知靠截图+编号、能力靠两阶段元素文档、执行靠独占前台。AgentNext 是"以隔离与治理为中心"的手机 Agent 工程系统：能力面收窄到可访问性语义与受控动作，换来同机并行、动作可治理、结果可核验。** 两者不是升级关系；对 AgentNext 最有价值的三件事是：把元素文档变成可积累的 Semantic memory、把截图编号变成受信任层的视觉接地通道、把评测建成独立 oracle 的冻结任务集——而不是照搬它的 PC+ADB 执行循环。
