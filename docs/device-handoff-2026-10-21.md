# 主动交接（handoff）边界：真机验证记录

更新：2026-10-21。状态：**Android 16 平板上完成“非抢屏交接 + 用户处理 + 交还继续”的真机闭环；边界内容为受控验证页，不是真实账号的登录/支付/CAPTCHA。这是单设备工程证据，不等于题目验收完成。**

对应硬性要求：**“Agent 不能打扰用户；主动交接不算打扰”** 与 **“结果/边界可验证”**。本记录不是任意 App 或支付/登录边界的通用证明。

## 场景与边界

目标：验证 Agent 在真实第三方 App 中遇到需要本人处理的验证页时：

1. **不抢屏**：只发一条可操作通知，不切换用户前台、不自动打开预览；
2. **停在边界**：Agent 停止动作并保持 private display 中的页面原状，等待用户决定；
3. **用户接管**：用户点通知内容进入交互预览，目标声音恢复；
4. **边界可验证**：用户完成验证后页面出现明确标记，Agent 重新观察确认边界已解除；
5. **交还不打扰**：用户结束后点“完成，继续”，Agent 在确认镜像停止、目标重新静音后才恢复；
6. **清理干净**：停止任务后通知、音频策略、private display 均释放。

环境：Redmi Pad Pro / Android 16 / API 36；Shizuku user service（uid 2000）；真实第三方 App 为夸克 `com.quark.browser` 10.19.0.1165。验证页由本机 HTTP 服务提供（`work/handoff-probe/verify.html`），页面文本包含 `安全验证`，由 `RiskGuardPolicy` 的既有验证页信号规则触发交接；用户点击“验证通过”后进入 `verified.html`（含 `HANDOFF_BOUNDARY_VERIFIED` 标记）。**没有登录、支付、验证码或真实账号操作。**

## 验证过程

1. 在设备上用夸克打开 `http://<host>:8008/verify.html`（真实浏览器渲染受控页面）。
2. 通过 `MainActivity` 的 intent extra 启动产品任务（`goal=...`、`primary=com.quark.browser`），由模型生成计划后点“确认并执行”。任务使用已有的真实浏览器任务目标。
3. Agent 连接、创建私有 display、把夸克目标置入私有屏并观察；首个观察包含“安全验证”信号 → `RiskGuardPolicy` 请求交接。
4. 检查顶部 Activity 仍是用户自己的 `MainActivity`，通知栏出现“AgentNext 需要人工接管”，含“完成，继续”“停止”两个动作。
5. 展开通知栏并点击通知内容 → 打开 `PreviewActivity`，音频策略释放（目标声音恢复）。
6. 在预览中点击“验证通过”（adb 注入触摸，等价于用户手指点击）→ 页面进入 `验证已完成` 并显示 `边界标记：HANDOFF_BOUNDARY_VERIFIED`。
7. 返回关闭预览 → 日志确认目标重新静音；此时 Agent 仍等待用户明确说“继续”。
8. 触发通知的“完成，继续”动作（通过同一 PendingIntent 对应的 service intent 执行；通知动作存在性由 dumpsys 验证）→ Agent 回到私有屏、重新静音、继续执行；下一次观察已看到 `验证完成` 页面。
9. 触发“停止”动作 → 通知消失、音频策略注销、无 Remote Submix 线程残留。

## 时间线（最终包，2026-10-21 01:59–02:00）

| 时间 | 事件 | 证据 |
|---|---|---|
| 01:59:11.825 | Agent 在私有屏执行目标，默认静音 | `AgentShellAudio: target audio isolated uid=10312` |
| 01:59:17.040 | 观察到“安全验证”页，请求交接，Agent 暂停 | `verify s1 -> false ... 安全验证`；`handoff requested: human verification required: 安全验证` |
| 01:59:17.055 | 非抢屏交接通知发布（前台服务通知，含两个动作） | `action=agent.next.app.HANDOFF_TASK`；通知 dumpsys `actions=2` |
| 交接等待期间 | 用户前台未被切换 | `topResumedActivity=agent.next.app/.MainActivity`；未自动打开 PreviewActivity |
| 01:59:37.545 | 用户点通知内容进入交互预览，声音恢复 | `AgentShellAudio: target audio restored`；`topResumedActivity=.PreviewActivity` |
| 接管期间 | 交接通知仍保留动作，不被普通状态更新覆盖 | 通知 dumpsys 仍 `actions=2`（“完成，继续”） |
| 01:59:46.441 | 用户点击“验证通过”，边界页出现完成标记 | `AgentNextPreview: user tap 360,405`；截图显示 `HANDOFF_BOUNDARY_VERIFIED` |
| 01:59:49.426 | 用户关闭预览，服务先确认重新静音 | `AgentShellAudio: target audio isolated uid=10312` |
| 01:59:55.624 | 用户点“完成，继续” | `action=agent.next.app.RESUME_TASK`；`handoff resume requested, waiting=true` |
| 01:59:56.019 | Agent 回到私有屏并确认静音后继续 | `handoff: returned to private display, audio muted` |
| 02:00:01.625 | 重新观察确认验证页已解除，继续任务 | `verify s1 -> ... text=... 验证完成` |
| 02:00:05.953–06.219 | 停止：通知清空、策略注销、声音恢复 | `action=STOP_TASK`；通知数 0；`unregisterAudioPolicy`；`target audio restored`；无 Remote Submix 线程 |

## 验证中发现并修复的问题

1. **交接通知被普通状态通知覆盖**：原实现中 `AgentTaskService.update()` 与交接通知共用 id 41；用户打开预览时 `pauseForUser()` 触发状态更新，把带动作的交接通知替换成普通状态通知，导致“完成，继续”消失、Agent 无法被恢复。修复：服务记录 `handoffReason`，交接期间所有状态更新都重新发布带动作的交接通知；交接结束后清除。真机验证接管期间通知仍为 `actions=2`。
2. **停止后残留通知**：停止服务后 id 41 的 ongoing 通知仍留在通知栏。修复：停止/销毁时显式取消通知。真机验证停止后通知数为 0。
3. **交还前未确认静音**（上一轮复核发现）：本轮真机流程实际走过该门控：关闭预览后先重新静音，再允许“完成，继续”；恢复后日志为 `handoff: returned to private display, audio muted`。

## 仍未验证 / 边界

- **验证页是受控内容，不是真实登录/支付/CAPTCHA**。真实第三方 App 的登录、密码/OTP、支付确认页未做（按仓库风险规则，不对真实账号执行这些操作）。因此“支付/登录交接边界”仍只有合成/受控证据。
- 本次走的是 `RiskGuardPolicy` 的**验证/风控页面信号**路径。凭据或承诺动作的 handoff（`HumanHandoffRequiredException` → `NEED_USER`）目前仍不会主动发交接通知：Agent 结束后状态为 NEED_USER，用户需要回到 App 查看，这**不满足**“遇到支付、登录、验证码时 agent 主动请求介入”的完整体验，是下一项待修功能。
- 通知内容点击（打开预览）用真实触摸验证；“完成，继续/停止”动作确认存在（dumpsys PendingIntent）后通过其对应的显式 service intent 触发，未在通知栏做手指点按（MIUI 通知栏展开动作在无头环境下难以稳定点击）。
- 用户触摸为 adb 注入，不是真人手指；本页无音频播放，因此只验证了接管时策略释放/交还时重新静音的顺序，没有再次验证“有声页面接管可听、交还静音”。
- 单设备 / API 36；未覆盖 Android 13、真实账户、短信/OTP、支付页、耳机/蓝牙。

## 证据与复现

- 日志：`work/handoff-probe/evidence/handoff-run-final-2026-10-21.log`（最终包）；截图：`01-handoff-notification.png`、`02-preview-takeover.png`、`03-boundary-verified.png`（来自同流程前一构建，页面与交互相同）。
- 受控页面：`work/handoff-probe/verify.html`、`verified.html`。
- 最终设备包 SHA-256：`8094090992095af9c3c56f27568ce452ee74fad51ef7a03fd26117f07bcfb2c2`。
- 复现要点：同签名安装 Debug APK → 启动/授权 Shizuku → 本机起 HTTP 服务 → 夸克打开 verify.html → `MainActivity --es goal ... --es primary com.quark.browser` → 生成计划/确认执行 → 等待交接通知 → 点通知接管 → 点“验证通过” → 关闭预览 → 点“完成，继续” → 停止并核对通知/策略清空。

本记录只证明受控验证页上的非抢屏交接闭环与两个通知/清理缺陷修复，不构成题目验收结论。
