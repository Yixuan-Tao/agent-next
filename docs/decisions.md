# 新项目决策记录

状态：**已确认；2026-10-21 增补 D12（目标 App 音频隔离）**。D1–D10 保留原决策，D11 明确当前业务执行与验收边界，D12 记录音频隔离契约与验证边界。本文件记录目标约束，不代表所有约束已实现；与早期设计提案冲突时，以本文件为准。
适用范围：`agent-next` 仓库及后续 `brain/`、`app/`。
相关：[Loop 重设计](loop-redesign.md)、[新项目保留清单](new-project-retention.md)、[capability 契约](device-capability.md)。

## 摘要

| ID | 决策 | 结论 |
|---|---|---|
| D1 | 默认自主级别 | **A3（提交需确认）**；A4 在 v1 不启用 |
| D2 | 外发消息 | **永远显式人工确认**，不自动发送 |
| D3 | 密码/OTP/生物识别 | **全部人工**；v1 不接 Autofill/Credential Manager |
| D4 | 记忆范围与保留 | 结构化、加密、最小化；事件 30 天，语义 90 天复验，偏好 180 天复验 |
| D5 | 仓库形态 | `agent-next` 单一模块化 monorepo；旧 `agent-phone` 只读冻结 |
| D6 | 接管方式 | **预览触摸为主**；主屏接管为显式 fallback |
| D7 | 文字/IME | 优先 target-bound `setText`；不做自有 IME；IME 场景走主屏接管 |
| D8 | testkit 发布 | 内部保留，不随产品发布 |
| D9 | Skill 层 | **可选加速层**；无坐标/原始设备权限；社区/自生成需验证与信任门 |
| D10 | Beta 平台底线 | **Android 13 / API 33**；目标 API 36；低于 33 需重新评估 |
| D11 | 默认执行与验收 | **全部通过 GUI**；直接业务写入退出产品路由；通用能力用无专用 skill 保留集独立验收 |
| D12 | 目标 App 音频隔离 | **按 UID 默认静音**；仅用户接管恢复；只读监看保持静音；失败阻止启动；不承诺按窗口/焦点隔离 |

## D1 默认自主级别：A3

**决策**

- 新任务默认 **A3**：agent 可执行可逆动作、把任务推进到承诺前一步；支付/下单/发送/删除等承诺动作必须停下来向用户确认，确认后由 agent 执行该动作。
- 全局默认可由用户在设置中降到 A0–A2；不能把全局默认提高到 A4，A4 只能任务级授权。
- 级别按累积上限理解：A0 只读 → A1 导航 → A2 可逆执行（承诺前停止并交还）→ A3 提交需确认。
- 用户可在任务级显式降到 A0/A1/A2；降低立即生效，不需要重规划。
- **A4 在 v1 不启用**。后续只对任务级显式授权、可枚举的可逆动作开放；A4 不改变承诺边界，不覆盖 B 类动作。
- 任何级别下，RiskGate 都可以升级为 Confirm/Handoff/Deny；agent 不得自行提升级别，需要提升时问一次。

**理由**

- A2 会在最关键的一步（提交）强制全量交还，丢掉上下文；A3 用确认卡保留上下文，同时把承诺决定留给人。
- 确认卡是设计中的人类网关核心；默认 A2 会让它长期没有真实使用场景。
- A3 仍满足“人类承诺边界”：人做承诺决定，agent 只执行已确认的机械动作。

**实现约束**

- 确认卡必须展示：动作、目标 App/页面、金额/收件人/内容、后果、可撤销性；默认焦点在取消；超时不自动确认。
- 确认必须是独立、明确的操作，不能与“继续任务”的同一个点击复用。
- 每次确认写入审计；承诺动作不得批量跳过。用户显式勾选“本任务同类可逆动作不再询问”时，仅对可逆动作生效。

**复审触发**

- 出现确认疲劳或橡皮图章式确认；或 Chain B 显示 A3 的提交错误率不可接受；或用户调研要求更保守的默认。

## D2 外发消息：永远人工确认

**决策**

- 发送短信、邮件、IM、评论、发帖、评价、邀请、跨 App 分享个人数据：**一律显式人工确认**，不因 A4 或“低风险”而自动。
- 可以自主：起草、编辑、保存草稿、复制到本地、准备收件人/附件。
- 定时发送视为一次承诺：设定时确认内容、收件人、时间；到点发送不再重复确认，但用户可在发送前取消。
- 确认卡必须展示收件人（完整地址/群名）、渠道、正文/附件、不可撤销性；用户可直接编辑。
- 确认后若正文、收件人、附件或时间发生变化，原确认失效，必须重新确认。
- 无确认则任务停在 `NEED_USER`，不得超时自动发送。

**理由**

- 外发消息是外部可见、通常不可撤销的承诺；错发、冒名、被注入后外泄的代价都高。
- 与 A3 的“承诺由人做”一致；消息是最典型的 B 类动作。

**实现约束**

- “发给自己的本地笔记/草稿”不算外发，可按 A2 自动。
- 拒绝任何“静默发送”“后台补发”“重试时跳过确认”。

## D3 密码与凭据：全部人工

**决策**

- Agent **不得**请求、读取、存储、输入、自动填充或代填：密码、口令、OTP/短信验证码、恢复码、Passkey、生物识别、安全问题答案、支付卡号/CVV。
- v1 **不接入** Android Autofill / Credential Manager，也不实现自有 IME 或剪贴板凭据通道。
- 观测层对密码/安全字段一律脱敏，不返回文本；预览在凭据输入期间暂停采集并隐藏画面。
- 凭据输入一律 hard handoff：agent 暂停，用户通过系统 UI 完成；agent 不读取输入结果，只在字段失焦、页面稳定后重新观察。
- 未来若要接 Credential Manager，只允许用户主动触发的系统 UI，agent 不可编程读取/填充；需单独安全评审，不属于 v1。

**理由**

- 密码/OTP/生物识别属于 A 类硬 handoff；capability 也明确不借用全局输入法/剪贴板。
- 凭据托管会把 agent 变成高价值攻击面，收益低、责任大。

**实现约束**

- 不允许通过无障碍树、OCR、截图、日志、崩溃报告间接获取凭据。
- 接管结束后重新绑定 target/display；不复用旧观察。

## D4 记忆：范围、保留期与清除

**决策**

| 类型 | 内容 | 存储 | 默认保留 | 清除 |
|---|---|---|---|---|
| Working | 当前 TaskGraph 节点、页面、尝试/失败、待确认问题 | 内存；暂停时可加密落盘 | 任务结束即删；暂停检查点 24h | 任务完成/停止/超时自动删 |
| Episodic | 动作、观察摘要、效果、接管、verifier 结果 | app 私有、加密 | 30 天 | 按任务删 / 全部清 |
| Semantic | 技能/导航知识（前置/后置、App/版本） | app 私有、加密 | 持续；90 天未验证则复验 | 用户可查看/删除 |
| User | 偏好/约束（尺码、预算、地址、收件人） | app 私有、加密 | 持续；180 天未用则复验 | 逐项删除 / 全部清 |

- **默认不持久化原始截图/帧、完整无障碍树、屏幕录制**。需要证据时仅在任务内使用；用户显式开启“保存证据”时，加密保存 7 天。
- 永不保存：密码/OTP/Passkey、支付卡 PAN/CVV、生物识别、剪贴板内容、与任务无关的屏幕文本。
- 敏感用户记忆（地址、电话、邮箱）逐项 opt-in；仅在当前任务需要时最小化提供给模型，不进入长期 prompt。
- 语义记忆不得包含 PII、原始屏幕文本或坐标；只存语义条件与验证结果。
- 语义记忆复验失败或与当前 App 版本不符时删除；用户偏好在复验确认前不作为默认值。
- 清除必须级联：事件、摘要、索引/embedding、检查点、证据；给用户“删除了什么”的确认。排除 Android 自动备份。
- 所有记忆默认仅存本机，不同步、不上传云端；模型调用只发送当前任务所需的最小上下文。
- 不用于训练；日志脱敏；模型供应商优先零保留；不后台批量上传。

**理由**

- 记忆是“会办事”的关键，但原始屏幕是 PII 密集资产；结构化 + 短保留 + 可清除是收益/风险平衡点。
- 证据式完成只需要任务内证据，不需要长期保存截图。

**复审触发**

- 隐私评审/法规要求；发生记忆相关事故；用户要求更长历史或跨设备同步。

## D5 仓库形态：单一 monorepo，旧仓库只读冻结

**决策**

- `agent-next` 是**单一模块化 monorepo**，目标模块包括 `capability/`、`device-api/`、`brain/`、`app/`、`testkit/`、`docs/`（当前已有 `capability/`、`device-api/`、`brain/`、`testkit/`、`docs/`）；依赖方向 `app → brain → device-api`，`capability` 实现 `device-api`，`brain` 不依赖 Android UI/shell。
- 不使用 submodule、不源码依赖旧仓库、不复制旧 `AgentLoop`/`TaskPlanner`。
- 旧仓库 `agent-phone` 在提交 `5846095` **只读冻结**：不保留维护分支、不接新功能；capability 修复只在新仓库做。旧仓库仅作为证据/参考与设备回归基线。
- 执行项：在旧仓库打归档 tag（如 `archive/agent-phone-5846095`）；旧仓库不再合并新提交。

**理由**

- capability/brain/app 需要原子演进，单 repo 避免版本错配、submodule 摩擦和双写。
- 旧 loop 是反面参考；只读保留可以避免“修旧 loop 顺手复用”的路径依赖。

**复审触发**

- app/brain 需要独立发布节奏或外部消费者；或仓库规模导致 CI 不可接受。

## D6 接管：预览触摸为主，主屏接管为显式 fallback

**决策**

- 默认：私有 display 的**预览镜像 + 用户触摸注入**（`userTap`/`userSwipe`）。用户触摸期间 agent 停止注入，结束后重新观察再继续。
- 保留**主屏接管**作为显式 fallback，仅在：安全/DRM 画面无法镜像；IME/密码管理器/系统 UI 必须在真实屏幕；预览不可用或延迟不可接受；目标 App 在虚拟 display 行为异常；用户主动要求。
- 主屏接管必须由用户触发或确认；agent 不得自动把任务移到主屏，也不得在主屏执行任务动作。
- 流程：暂停快循环 → 记录状态 → `moveTargetToDisplay(0)` → 用户操作 → 用户点“结束接管” → 重新观察/绑定 → 继续或重规划。
- 接管期间用户切换 App/锁屏：任务上下文失效，询问继续/停止；不复用旧观察、不重放未确认副作用。
- 审计记录接管原因/起止/目标 App；不记录屏幕内容。

**理由**

- 密码、CAPTCHA、支付认证等硬 handoff 往往需要真实键盘/系统 UI；只做预览触摸会把这些场景堵死。
- 能力层已实现且有回归，保留成本低；但它必须是“人的通道”，不是 agent 的隐藏执行路径。

**复审触发**

- 预览/触摸已能覆盖全部 handoff 场景；或主屏接管导致上下文/安全问题不可控。

## D7 文字/IME：setText 优先，不做自有 IME

**决策**

- P0：非敏感文本用 target-bound `setText` 注入；不使用剪贴板、不借用全局输入法、不发全局 key event。
- P1：需要 IME 组合/联想/自动补全（中文输入、搜索建议、密码管理器）时，走主屏接管，由用户用系统键盘输入；agent 不模拟按键。
- v1 不安装/启用自有 IME，不读取剪贴板。
- 每次文字注入后验证字段/页面效果；失败则回退到接管或换路径。
- 密码/OTP 字段永远不走 `setText`（见 D3）。

**理由**

- capability 明确避免全局 IME/剪贴板；自有 IME 是安全与合规高风险，且难以覆盖厂商键盘差异。
- `setText` 可绑定 target/窗口，易验证，足以覆盖大多数表单。

**复审触发**

- Chain A/B 显示 `setText` 在主流 App 的失败率过高；或系统提供更安全的受限输入 API。

## D8 testkit：内部保留，不随产品发布

**决策**

- `testkit/` 继续放在 monorepo，用于 host/设备回归、故障注入和准入探针；不进入 `:app` 发布产物，不发布 Maven 构件。
- 保持源码/脚本/fixture 可独立构建；不包含 APK、密钥、原始截图、设备日志或 PII。
- 设备测试工具默认内部使用；如未来对外，另做脱敏发布，不与产品版本绑定。

**理由**

- 这些工具是回归资产，也是攻击面和维护负担；不应进入用户设备上的产品包。
- 旧仓库的测试脚本只作参考，新仓库只保留必要部分。

## D9 Skill 层：可选、无坐标、受治理

**决策**

- Skill 是**可选加速/可靠性层**，不是主控制流。没有匹配 skill 时，FastLoop/通用规划必须仍能工作。
- Skill body 允许 `workflow`、`prompt`、`hybrid`；确定性 `SkillWorkflowPolicy` 只执行 workflow/hybrid 的语义操作，prompt 由 `PromptSkillPolicy` + `SkillModel` 模型端口加载（旧 chat-completions 文本/单帧 PNG API 已移植，app prompt 模式已接线并真机 PASS）。
- Skill 工件和 skill-facing API **不得包含坐标、`Point`、原始 `DeviceApi`、shell/输入注入**。只允许语义选择器和语义操作；坐标只能存在于受信任的 device strategy。
- Skill 动作必须经过 FastLoop 的 `RiskGate`/`HumanGateway`；skill 自己声明的风险不可信，不能绕过承诺/凭据/接管边界。
- `COMMUNITY`/`SELF_GENERATED` skill 必须经过验证（静态检查、回放/矩阵、行为验证）和信任门才能安装；不得自动上线。需记录版本、来源、digest、验证报告，支持撤销/回滚。
- 自进化只能产生 candidate，不能自行批准；高风险 skill 需要人审。

**理由**

- 通用 agent 的长尾 App 不可能全靠手写流程；skill 能沉淀重复经验，但固定脚本会退化成 RPA。
- 社区/自生成 skill 是不可信第三方代码；公开 skill 生态已出现 prompt injection、凭据窃取和供应链攻击。
- 坐标是设备/分辨率绑定的；把它留在受信任适配层，skill 才可能跨设备复用。

**实现约束**

- `SkillPackage` 只含 contract、body、permissions、applicability、verifier、trust、provenance。
- `SkillWorkflowPolicy` 把语义操作映射成 `LoopAction`，不直接调用 `DeviceApi`。
- `SkillRegistry` 对不受信来源强制 validation；撤销后不得再被检索/执行。
- Prompt skill 不得由确定性执行器直接跑；模型策略仍需经过 action space 的坐标禁令和 RiskGate。

**复审触发**

- 社区 skill 验证成本过高或误报率不可接受；或 skill 生态出现新的供应链攻击模式。


## D10 Beta 平台底线：Android 13

**决策**

- 内部 Beta 的 `minSdk` 为 **33（Android 13）**，`compileSdk/targetSdk` 保持 36。
- 第二台验收手机为 Android 13/API 33；若实际低于 33，需另开兼容阶段或更换设备。
- API 33 必须实测：Shizuku 用户服务、私有 display、target、observe/frame、定向输入、
  task move、preview mirror；mirror 不可用时必须显式 fallback，不能假装实时预览。
- API 33 的前台服务类型使用 `dataSync`；API 34+ 使用 `specialUse`。

**理由**

- 用户目标是内部 Beta + 一台 Android 13 手机；当前 minSdk 36 无法安装。
- 只降低 minSdk 而不做真机兼容验证会制造“构建通过但设备不可用”的假通过。

**复审触发**

- 第二台手机低于 API 33；或 Android 13 mirror/服务类型无法满足 Beta 验收。
## D11 纯 GUI 内部 Beta

日期：2026-10-20。状态：已确认，迁移与验收尚未完成。产品终点、接口和 M0–M6 路线见 [纯 GUI Beta 说明](pure-gui-beta.md)。

**决策**

- 默认日常执行与正式验收均通过 GUI 完成业务任务；不设“验收纯 GUI、日常仍走直接业务写入”的双重口径。
- 允许界面观察、截图、控件定位、点击、`setText`、滚动、返回、App 启动与基础设施查询。
- CalendarContract、MediaStore、AlarmClock、私有业务 API 和直接完成业务的 deep link 退出产品执行路由；历史实现保留为参考。
- 外部测试 oracle 可独立读取最终数据，但不得向执行中的 agent 提供这些数据。
- GUI skill 是可选加速层，遵守 D9；所有准备动作、候选重试与组合动作均须经过统一执行器。
- 任务必需结果不得被 skill 或重规划删除；全部通过任务级核验才可报告 DONE。
- 默认 A3、凭据人工、主屏接管确认、记忆与平台边界继续遵循 D1–D10。

**理由**

最终目标是验证和交付通用手机界面操作能力。日历/provider 写入虽可完成特定业务，却不能证明 GUI 泛化；混合口径会掩盖通用执行、恢复和验证中的缺口。

**实施与验收约束**

- 当前 runtime 仍注册直接业务写入，必须迁移后重新验收；历史 PASS 不自动升级。
- 24 个开发任务 + 12 个无 App 专用 skill 的保留任务，两台设备各三轮。每台开发集自主完成率 ≥80%、保留集 ≥60%；人工辅助单列。
- 误报完成、未经确认提交、停止后新派发输入在验收中必须为零；故障与真实二十分钟并发测试通过。
- 保留集用于针对性修复后转为开发数据，替换新保留集；不得挑选容易任务来降低门槛。
- 本期仅内部 Beta，不包含社区 skill 上线、iOS 或公开商店发布。

**对早期记录的影响**

直接接口日历/目录/闹钟记录继续保留为历史证据，不能计入纯 GUI 成绩；早期设计中的 domain verifier 仍可用于 GUI 可见结果验证，agent 不得借此读取隐藏业务数据。测试端 oracle 与 agent 运行时 verifier 必须分离。

## D12 目标 App 音频隔离

日期：2026-10-21。状态：已实现并在 Redmi Pad Pro / Android 16 上完成路由验证；第二设备、真实第三方播放路径与 Beta 验收未完成。实现与证据见[音频隔离验证记录](device-audio-isolation-2026-10-21.md)。

**决策**

- Agent 在私有屏操作目标 App 时，该 App **默认静音**：按目标 UID 注册 loopback 音频策略，把播放路由到本地丢弃缓冲，不修改全局音量、系统静音或其他 App 路由。
- 只有**用户明确接管**才恢复声音：交互预览接管，或显式把目标移到主屏由用户控制。电脑只读监看不恢复声音。
- 交还 Agent、停止镜像、移回私有屏或释放目标后必须重新静音；策略生命周期与 target 所有权同源。
- 隔离建立失败时**阻止目标启动**，不静默降级为有声执行；释放失败保留所有权以便重试。
- 音频只在本地短暂缓冲后丢弃，不保存、不上传；日志不记录音频内容；app 不申请麦克风权限。
- 按 UID 隔离不等于按 display/窗口隔离：同一 UID 的其他窗口、通知音和后台播放会一起静音；不承诺完整音频焦点隔离。
- 依赖 Shizuku shell 权限与 Android 隐藏音频 API；注册被拒、record 建立失败或清理不确定都按失败处理。凭据接管期间的音频与采集隔离仍按 D3 另行执行。

**理由**

目标体验是用户继续正常使用手机时 Agent 在后台操作 App；如果目标 App 在私有屏播放声音，会打扰用户、暴露 Agent 正在操作的内容，也无法证明“不打断用户”。同时不能为静音目标而全局静音或降低设备音量，否则会误伤用户主屏应用。UID 级路由是当前无 Root/无系统签名条件下可验证的折中。

**实现约束**

- 仅 capability/shell 层执行；brain 与 GUI skill 不接触音频策略或音频数据。
- 镜像通道按交互接管、手机只读、电脑只读独立编号；各自可启动/停止，不影响其他 Surface。镜像 RPC 串行并带超时补偿；开始失败时仅补偿对应通道，避免“远程可能已启动但本地未知”的永久卡死。
- 交还控制必须以“确认重新静音”为前提：关闭预览、handoff 通知继续或移回私有屏时，先停止交互镜像并确认静音，未确认则保持暂停并提示，不得先恢复 Agent 再补救。
- 测试覆盖 bind→muted、只读→muted、接管→audible、回交→muted、释放→released、创建失败不提交、清理不确定保留所有权、同 UID 重复 bind 幂等、主屏失败保留所有权、释放失败可重试、目标被切前台时交还声音。
- release/teardown 必须逐步尝试所有清理步骤（帧、target、display、音频策略），不能因前一步失败跳过音频注销；关闭策略时 stop/release/unregister 逐项尝试并聚合异常。
- 真机验证必须用系统 dump 证明目标 UID 离开扬声器输出并进入策略专属 remote-submix，其他 UID 路由不变、全局音量不变；声学确认与真实硬件 offload 路径单列未验证。
- 设备 APK 必须同签名覆盖安装并保留数据；不能用卸载清数据掩盖签名问题。user-service 版本/标签需随协议变更递增，避免旧进程被复用。

**复审触发**

- 第二台设备/Android 13 或真实 App（语音、视频、游戏、DRM）出现无法静音、音频残留或误静音。
- 目标 App 的音频焦点/设备切换行为对用户造成可见干扰。
- Android 版本变化使隐藏 API 或 shell 权限不可用，或失败阻止启动过于频繁。

## 决策依赖

- D1 依赖 D2/D3 的承诺边界：A3 只对“已确认的承诺动作”执行，凭据仍全部人工。
- D6/D7 是 D3 的实现通道：凭据/IME 场景走主屏接管，不引入自有输入通道。
- D4 限制证据留存：D6 的接管审计只记事件，不记屏幕内容。
- D5 决定 brain/app 的模块边界：brain 只依赖 `device-api`，不依赖 `capability` 的 Android 实现。
- D11 收紧业务执行与验收边界；它不扩大 D1–D3 的权限，也不改变 D5/D9 的分层要求。
- D12 依赖 D6 的接管语义与 D3 的凭据隔离：恢复声音只跟随用户接管/主屏控制，不改变凭据期间的采集与预览限制；实现留在 capability shell 层，不进入 brain 或 skill。

## 历史执行摘要（2026-10-19，非当前路线）

以下保留早期工作记录，不代表纯 GUI Beta 的现状或发布结论。当前实施顺序以 [M0–M6](pure-gui-beta.md) 为准；社区 registry 等早期后续设想不纳入本期。

1. 在旧仓库打归档 tag，确认只读。
2. `device-api` 首版 + capability adapter host 契约 + app shell 真机 smoke（connect/create/open/observe/frame/input/task move）已完成；瞬时窗口变化已改为可重观察的 `WINDOW_CHANGED`；`FastLoop` 确定性骨架、17 个 host suite、`SlowLoop`（replace/insertBefore/remove/ask/stop）/`LoopRunner` 跨 attempt 预算、FastLoop 与 LoopRunner 真机京东 smoke、可选 `SkillPackage`/`SkillWorkflowPolicy`/`PromptSkillPolicy`（模型端口）/`SkillRegistry`、旧 chat-completions 文本/单帧 PNG API 移植、app prompt 模式真机 smoke、视觉 verify/extract 真机 smoke、TaskPlanner、SessionRunner/SubgoalRunner（多 target + verifier + slow-loop 主图 + StageData）、app bridge（prompt 工厂 + 视觉 verifier + planner→session→prompt→visual 集成）、AndroidPreviewController（host preview/userTouch 端口）、Android 16 平板 preview mirror/userTap/主屏接管 smoke、产品 MainActivity/AgentRuntime/计划审阅/危险动作确认壳、bounded scroll fallback、共享 preview/touch pause/resume、前台服务/通知 stop action、真实首页任务 DONE、加密存储设备验证、calendar domain skill、Chain A（Quark 天气 → 日历提醒）、JD 搜索段、内部 release APK 签名、Settings/privacy（加密状态/memory 清除）已完成；下一步 JD 商品详情/加购/购物车（Chain B 剩余）、20 分钟后台/进程回收、Android 13 安装验收、社区 registry、多帧与更多场景。
3. 定义确认卡/接管 UI 的最小字段与审计事件。
4. 落实记忆加密、保留期任务与清除级联。
5. 在 Chain A/B 验收中记录确认次数、接管次数、误提交/错发为 0。

---

变更方式：新增/修改决策请追加编号或在原条目下标注日期与理由；不要让代码实现绕过本文件。
