# agent-next

新项目的 **capability 种子仓库**。当前包含设备能力层、纯 JVM 设备边界、领域骨架、最小 app shell 和测试工具；尚不包含完整 brain loop 或产品 UI。

来源：从旧仓库 `agent-phone`（提交 `5846095`）按 [新项目保留清单](docs/new-project-retention.md) 复制。旧仓库保留为证据和参考，不在这里通过 submodule/源码依赖引用旧 `AgentLoop`/`TaskPlanner`/`MainActivity`。

## 当前包含

```text
capability/   Android library：AIDL、Shizuku 连接、display/target、
              observation/frame、定向输入、preview mirror、主屏 task move、安全策略
device-api/   纯 JVM 设备边界：display/target/observation/frame/input/task-move 端口与契约
brain/        纯 JVM 骨架：TaskSpec/TaskGraph/FastLoop/SlowLoop/SkillPackage/Memory/Effect/RiskGate/HumanGateway
app/          最小 shell：device-api smoke + FastLoop + LoopRunner + 真实模型 prompt skill
testkit/      合成前台 App、准入探针、设备故障探针、旧 loop 的布景脚本（仅参考）
tools/        capability/device-api/brain 的 host 回归脚本
docs/         loop 重设计 + 保留清单 + capability 契约 + 决策记录 + 设备回归
```

尚未包含：

- `brain/` 的任意 DAG 重排、多帧/视频、社区 registry：当前有 TaskPlanner、SessionRunner、FastLoop + SlowLoop、workflow/prompt skill（真实文本+PNG API）、视觉 verify/extract、验证/信任门；
- preview mirror + 用户触摸接管的真机链路；
- `app/` 的产品 UI/自然语言入口/人工网关/前台服务：当前只有最小 smoke shell；
- 新 loop 的真实设备验收；
- 真正的视频/文字接管 UI（capability 只提供底层能力）。

## 构建

需要 Android SDK 36、JDK 21、Gradle wrapper。Beta 基线为 Android 13（API 33）及以上；第二台 Android 13 手机待连接验收。`local.properties` 不提交，按本机 SDK 路径创建。

```sh
JAVA_HOME=/path/to/jdk21 \
GRADLE_USER_HOME=/path/to/gradle-cache \
./gradlew --offline :capability:assembleDebug :app:assembleDebug
```

`capability` 是 Android library；`app` 是用于真机 smoke 的最小 shell，不是产品 UI。

## host / 设备回归

```sh
QUAL_JAVA_HOME=/path/to/jdk21 sh tools/test-service.sh
GRADLE_USER_HOME=/path/to/gradle-cache \
QUAL_JAVA_HOME=/path/to/jdk21 sh tools/test-shizuku-adapter.sh
QUAL_JAVA_HOME=/path/to/jdk21 sh tools/test-device-api.sh
QUAL_JAVA_HOME=/path/to/jdk21 sh tools/test-capability-adapter.sh
QUAL_JAVA_HOME=/path/to/jdk21 sh tools/test-brain.sh
```

已验证：

- `:capability:assembleDebug` BUILD SUCCESSFUL；
- `:app:assembleDebug` BUILD SUCCESSFUL；
- `test-service.sh` 全部 PASS（服务/display/target/observation/frame/packet）；
- `test-shizuku-adapter.sh` PASS（真实 13.1.5 AIDL 适配、UID/callback/cancel 竞态）；
- `test-device-api.sh` PASS（FakeDeviceApi 上的 display/target/observation/input/task-move 契约）；
- `test-capability-adapter.sh` PASS（BlockingDeviceApi + FakeConnectionDriver 契约，含 preview/user touch 端口）；
- Settings/privacy：加密 config 状态、episodic memory 计数与清除、隐私说明真机 PASS；
- Release APK：已用内部 keystore 签名，minSdk 33/target 36，release 的 PreviewActivity 不导出；待 Android 13 安装验证；详见 [docs/release-beta.md](docs/release-beta.md)；
- 本地加密存储（AES-GCM + AndroidKeyStore）：`model-config.enc`、`episodic.enc` 真机迁移验证 PASS；明文 config 已删除；
- `test-brain.sh` 全部 PASS（17 个 suite：TaskGraph/autonomy/spec/risk/effect/confirmation/memory/slow-loop/skill/fast-loop/slow-loop-runner/skill-feature/model-api/task-planner/session-runner/session-bridge/session-integration）；
- 设备首轮回归 PASS：Redmi Pad Pro / Android 16 上的私有 display、京东观察、单帧、中文 setText、定向触摸、主屏 task move、SharedMemory 帧内存；详见 [docs/device-regression-2026-10-19.md](docs/device-regression-2026-10-19.md)；
- capability adapter 真机 smoke PASS：connect/create/open/observe/frame/tap/setText/search/task-move/release；主屏 agent 输入被拒绝；详见 [docs/device-adapter-smoke-2026-10-19.md](docs/device-adapter-smoke-2026-10-19.md)；
- FastLoop 真机 smoke PASS：京东首页 → 搜索 → setText → 提交 → 结果页，DONE actions=4；详见 [docs/device-fast-loop-smoke-2026-10-19.md](docs/device-fast-loop-smoke-2026-10-19.md)；
- LoopRunner 真机 smoke PASS：第一轮 NO_EFFECT → SlowLoop 替换 approach → 第二轮成功，DONE attempts=2 replans=1 totalActions=6；详见 [docs/device-slow-loop-smoke-2026-10-19.md](docs/device-slow-loop-smoke-2026-10-19.md)；
- 兼容性准入：Android 16 平板上 `mode=compat` PASS，shell service 报告 `PREVIEW_MIRROR`；Android 13 手机待连接；详见 [docs/android-13-compat.md](docs/android-13-compat.md)；
- Product UI/plan review smoke：真实模型四段计划、确认按钮、SessionRunner 接线 PASS；s2 加入 bounded scroll fallback 后 verifier 2/2 通过，DONE actions=1 attempts=2；详见 [docs/product-ui-plan-smoke-2026-10-19.md](docs/product-ui-plan-smoke-2026-10-19.md)；
- Chain A PASS：Quark 天气结果 + 降雨信息视觉 verifier 2/2，calendar domain skill 创建“带伞”明天 09:00 提醒，DONE actions=3 attempts=6；详见 [docs/chain-a-attempt-2026-10-19.md](docs/chain-a-attempt-2026-10-19.md)；
- 20min foreground soak PASS：13:05 start → 13:10/13:15 前台服务存活 → 13:25 定时停止，进程未崩溃；不是完整 agent 并发；详见 [docs/foreground-soak.md](docs/foreground-soak.md)；
- Chain B cart PASS：JD 首页 → 购物车，视觉 verifier s2/s3 true，DONE actions=1 attempts=3，未结算；
- JD result-card policy 已加入；s2 被首页广告浮层阻塞，自动化加购未 PASS；
- JD 规划器已强制独立加购子目标（4 subgoals），执行仍停在搜索；
- JD 搜索确定性 policy 已加入但自动化加购仍停在 s2；
- JD 加购自动化仍未 PASS：搜索 s2 停滞；人工加购 PASS 数量 3→4；
- JD 加购自动化 policy 已加入（未验证）；人工加购 PASS：数量 3→4；
- JD 详情/加购人工 PASS：验证后详情 136 nodes，加入购物车后数量 3→4，未结算；agent 自动化加购待做；详见 [docs/chain-b-notes.md](docs/chain-b-notes.md)；
- Release end-to-end PASS：文件管理器安装/非 debuggable/config 导入/Shizuku/JD 首页 DONE actions=2 attempts=4；Android 13 待验；
- Release verification：`tools/verify-release.sh` PASS（签名/SDK/launcher/exported/checksum）；
- Release artifacts：`dist/agent-next-beta-0.1.apk` + SHA-256 `5d365641aa2c73f9ad245f3b8f92ae253b3e50019e4ea8ab316243738c53b080`；隐私说明与 release notes 草案已写；
- Model retry/budget：408/429/5xx 有界退避 + 80 calls/任务上限；host test PASS；
- Shizuku user-service kill：执行中 kill -9 → FAILED post-action observe failed，无 DONE/支付；
- Human handoff stop：等待期间 STOP_TASK → not approved、service 停止、不 DONE；
- Human handoff resume：主屏 → RESUME_TASK → 回私有 display → 重新 observe 真机 PASS；验证页未完成会再次 handoff（正确）；
- Human handoff trigger：京东验证 → 主屏 + 通知 action 真机观察 PASS；resume 点击仍待验收；
- Target-loss fault：执行中关闭 JD → FAILED post-action observe failed，无 DONE/支付；
- Network fault injection：规划中断网 → FAILED，无动作/无 DONE；详见 [docs/fault-injection-2026-10-19.md](docs/fault-injection-2026-10-19.md)；
- Human handoff：风险页 → 主屏 + 通知“完成，继续/停止” + 回私有 display 已接入；真机链路待验收；详见 [docs/human-handoff.md](docs/human-handoff.md)；
- Risk guard PASS：JD 进入“京东验证”时不再盲点，FastLoop 返回 NEED_USER actions=0；Chain B 仍因详情页/风控阻塞；
- JD search PASS：搜索入口 → 输入 → 提交 → 结果页，DONE actions=2 attempts=3；加购/购物车段仍被商品详情无障碍阻塞；详见 [docs/chain-b-attempt-2026-10-19.md](docs/chain-b-attempt-2026-10-19.md)；
- Calendar domain skill PASS：CalendarContract 创建“带伞”明天 09:00 提醒，4 个 calendar 子目标 verifier 通过 DONE；详见 [docs/calendar-domain-skill-2026-10-19.md](docs/calendar-domain-skill-2026-10-19.md)；
- Foreground service smoke PASS：dataSync|specialUse、通知 Stop action、RUNNING 启动/DONE 停止；详见 [docs/foreground-service-smoke-2026-10-19.md](docs/foreground-service-smoke-2026-10-19.md)；
- Shared runtime preview/touch pause PASS：预览打开暂停 agent、触摸注入、关闭后 resume，4 段 plan DONE；
- Preview mirror/touch 真机 smoke PASS：TextureView 镜像京东私有 display，userTap 映射到 720×1280 并进入 11.11 会场；主屏接管/返回私有 display 开关验证；详见 [docs/preview-touch-smoke-2026-10-19.md](docs/preview-touch-smoke-2026-10-19.md)；
- Prompt Skill 真机 smoke PASS：真实 DeepSeek 模型输出 SetText/Tap/Wait 语义动作，经 RiskGate 执行，JD 结果页 verifier 通过，DONE actions=4；视觉 verify `verified=true matched=2 missing=0`，extract 30 字摘要/7 实体；详见 [docs/device-prompt-skill-smoke-2026-10-19.md](docs/device-prompt-skill-smoke-2026-10-19.md)。

## 新项目边界

- capability 只提供“设备身体”：私有 display、目标会话、观察/截图、定向输入、预览镜像、主屏 task move、安全校验。
- 新 brain 不得直接依赖 Android `ShellService`/坐标；只能通过 capability API。
- 旧 loop 的固定计划、预设、条件/预算数字不作为新设计基线。
- 测试工具中的旧 model-loop 脚本只用于布景/参考，不代表新 loop 验收。
- 文档计划：[docs/loop-redesign.md](docs/loop-redesign.md)；保留/迁移：[docs/new-project-retention.md](docs/new-project-retention.md)；已定决策：[docs/decisions.md](docs/decisions.md)；adapter 真机 smoke：[docs/device-adapter-smoke-2026-10-19.md](docs/device-adapter-smoke-2026-10-19.md)；FastLoop 真机 smoke：[docs/device-fast-loop-smoke-2026-10-19.md](docs/device-fast-loop-smoke-2026-10-19.md)；slow-loop 真机 smoke：[docs/device-slow-loop-smoke-2026-10-19.md](docs/device-slow-loop-smoke-2026-10-19.md)；prompt skill 真机 smoke：[docs/device-prompt-skill-smoke-2026-10-19.md](docs/device-prompt-skill-smoke-2026-10-19.md)；preview/touch 真机 smoke：[docs/preview-touch-smoke-2026-10-19.md](docs/preview-touch-smoke-2026-10-19.md)；产品 UI/plan review：[docs/product-ui-plan-smoke-2026-10-19.md](docs/product-ui-plan-smoke-2026-10-19.md)；前台服务：[docs/foreground-service-smoke-2026-10-19.md](docs/foreground-service-smoke-2026-10-19.md)；Chain A 尝试：[docs/chain-a-attempt-2026-10-19.md](docs/chain-a-attempt-2026-10-19.md)；calendar domain skill：[docs/calendar-domain-skill-2026-10-19.md](docs/calendar-domain-skill-2026-10-19.md)；Chain B 尝试：[docs/chain-b-attempt-2026-10-19.md](docs/chain-b-attempt-2026-10-19.md)；release：[docs/release-beta.md](docs/release-beta.md)；设置/隐私：[docs/settings-privacy.md](docs/settings-privacy.md)；人工接管：[docs/human-handoff.md](docs/human-handoff.md)；故障注入：[docs/fault-injection-2026-10-19.md](docs/fault-injection-2026-10-19.md)；模型重试：[docs/model-retry.md](docs/model-retry.md)；隐私说明：[docs/privacy.md](docs/privacy.md)；release notes：[docs/beta-release-notes.md](docs/beta-release-notes.md)。
- capability 约束：[docs/device-capability.md](docs/device-capability.md)。

## 关于目录

当前 `agent-next/` 位于旧工作区下，是一个**独立 Git 仓库**，便于在文件沙箱内创建；正式接入远程前可整体移动到同级目录，不影响内部路径。
