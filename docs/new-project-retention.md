# 新项目保留清单与迁移评估

状态：**基于当前仓库的模块化审查，未创建新 repo**。审查覆盖源码、docs、tools、work 中的测试程序/证据；没有逐行审阅所有原始日志。

结论：**新项目应保留“设备身体 + 测试工具 + 事实文档”，重写“任务大脑 + 任务 UI”；旧 loop 不作为新架构基础。**

## 0. 一句话结论

- **保留并复用**：Shizuku/AIDL/私有 display/目标会话/观察/输入/预览镜像/触摸接管/主屏接管/安全策略/协议校验；host 测试、准入探针、合成前台测试 App、故障注入工具。
- **适配后复用**：OCR、VisionModel 接口、TaskResult/日志/通知、购物车 verifier、前台服务/悬浮面板、App 授权选择、设备脚本。
- **重写**：AgentLoop、TaskPlan/TaskPlanner、TaskStage、TaskRuntime、MainActivity 任务区/预设、条件—预算—单步决策逻辑、模型 action prompt。
- **归档为证据，不当规范**：Quark/JD/Planner 的旧 loop 验收、固定链、JD 预设、旧条件文本、旧预算数字。
- **不要复制**：`private/`、模型 API key、原始设备日志/截图中的 PII、debug keystore、旧 APK/第三方 APK、`work` 全量目录。

## 1. 分层盘点

| 层 | 当前内容 | 判定 |
|---|---|---|
| 设备身体 | AIDL、Shizuku、shell service、display/target、observation/frame、input、preview mirror、task move | **保留** |
| 安全/协议 | target/display token、UID/窗口校验、packet 校验、budget、FrameSafetyPolicy、ServicePolicy | **保留** |
| 测试工具 | host 测试、qualification probes、合成前台 App、task move/mirror/parcel 探针 | **保留** |
| 结果出口 | TaskResult、JSONL、通知 | **适配** |
| 感知工具 | OCR、VisionModel 接口/HTTP adapter | **适配/重写 prompt** |
| 领域核验 | CompletionVerifier、CartVerifier/CartExpectation | **保留为插件/原语** |
| 任务大脑 | AgentLoop、TaskPlan、TaskPlanner、TaskRuntime | **重写** |
| 任务 UI | MainActivity 任务区、预设、条件/购物车输入 | **重写** |
| 系统 UI | AgentTaskService、悬浮面板、预览/接管 | **适配** |
| 文档 | 设备/API/验证/准入协议 | **保留事实，归档旧设计** |

## 2. 可以直接保留（设备身体）

### 2.1 Binder / Shizuku / 连接

- `app/src/main/aidl/agent/phone/ipc/IAgentShell.aidl`
- `connection/ShizukuConnection.java`
- `connection/ShizukuBackend.java`
- `connection/ConnectionCoordinator.java`
- `connection/ConnectionState.java`
- `connection/UserServiceProtocol.java`
- `connection/DisplaySession.java`、`TargetSession.java`
- `executor/ClientPolicy.java`、`InstalledAppIdentity.java`、`ProtocolPolicy.java`

理由：已经处理真实 Shizuku 13.1.5、UID 2000、安装 App UID、协议版本、服务重启、超时/迟到响应、Binder 清理。这些是脏活，重写没有收益。

注意：新项目可以重新编号协议版本；保留语义和测试，不必保留旧版本号。

### 2.2 私有 display / 目标会话

- `executor/DisplayLifecycle.java`、`AndroidDisplayResources.java`
- `executor/TargetLifecycle.java`、`AndroidTargetObserver.java`
- `executor/ShellService.java`
- `executor/ObservationBudget.java`、`FrameLease.java`
- `executor/FrameSafetyPolicy.java`
- `executor/PrimaryTaskPolicy.java`

保留：创建/检查/释放、display 0 禁令、目标绑定、窗口归属、焦点冲突、observer 生命周期、失败清理。

### 2.3 观察 / 帧 / 协议

- `connection/TargetObservation.java`
- `connection/FrameEvidence.java`
- `connection/FramePackets.java`
- `connection/ObservationPackets.java`
- `connection/TargetTap/Swipe/Back/Text*Packets.java`
- 对应 host 测试

保留：字段精确校验、SharedMemory 所有权、序号/窗口/UID 校验、PNG/黑帧/大小限制、失败清理。这些是后续 loop 的证据基础。

### 2.4 输入

- `executor/AndroidInputDispatcher.java`
- `executor/TargetLifecycle` 的 `UserInput` 抽象
- 预览触摸接管相关 AIDL `userTap/userSwipe`

保留：display/UID 定向注入、不借全局输入法/剪贴板、tap/swipe/文字/返回；新 loop 直接复用为 actuator。

### 2.5 交接 / 预览

- `executor/TargetTaskMover.java`（主屏接管）
- `executor/PreviewMirror.java`
- `preview/PreviewActivity.kt`（TextureView 视频 + 触摸）
- `preview/PreviewStore.kt`（如需截图 fallback，可适配；否则删）
- `TaskRuntime` 的 handoff/mirror/userTap 接口（UI 适配）

保留：私有 display → 主屏 task move、display mirror + TextureView、AUTO_MIRROR、同 shell context、触摸接管。这些是当前最有价值的产品差异点。

### 2.6 安全/身份策略

- `FrameSafetyPolicy`、`ServicePolicy`、`PrimaryTaskPolicy`、`ClientPolicy`、`ProtocolPolicy`
- `ObservationBudget`、`FrameLease`
- 安全窗口/foreign window/密码字段不返回等规则

保留并作为新项目的**不变量**；新 loop 只能通过这些能力层访问设备。

### 2.7 host 测试

- `app/src/test/java/agent/phone/connection/*`
- `app/src/test/java/agent/phone/executor/*`
- `tools/test-service.sh`
- `tools/test-shizuku-adapter.sh`
- `app/src/test/java/agent/phone/core/*`（见“适配”）

保留为 capability regression。新项目第一步应先让这些测试过，再写新 brain。

## 3. 适配后复用

| 资产 | 处理 |
|---|---|
| `model/VisionModel.kt` | 保留接口思路；重写 prompt/action schema，适配技能/证据/不确定性 |
| `model/ScriptedVisionModel.kt` | 保留为 test fixture；action 脚本需适配新协议 |
| `ocr/OcrEngine.kt` | 保留为感知工具；不作为唯一核验 |
| `engine/TaskResult.kt`、`TaskRunStore.kt`、`CompletionNotifier.kt` | 保留结果/日志/通知骨架，字段改为新 loop 的 task/node/skill/risk/handoff |
| `core/CompletionVerifier.java` | 保留为文字证据插件；不作为唯一成功标准 |
| `core/CartVerifier.java`、`CartExpectation.java` | 保留为购物领域 verifier 示例/插件 |
| `service/AgentTaskService.kt` | 保留前台服务/通知/悬浮入口；控制面板适配新状态机 |
| `preview/PreviewActivity.kt` | 保留视频/触摸；增加文字输入/确认卡需新做 |
| `ui/MainActivity.kt` 的连接/目标/应用选择 | 保留交互和 App 选择（默认全选/取消/持久化）；任务区重写 |
| `ui/MainActivity.kt` 的 allowed apps | 直接复用产品行为：默认全部、可取消、恢复全部、持久化 |
| `work/model-loop/*_setup.py`、mock JSON | 适配新 UI/新 action schema；不要直接当新 loop 测试 |
| `tools/qualification/*` | 保留探针/测试，扩展为新 loop 的输入/帧/预算/会话回归 |
| `work/foreground-20261016/program/*` | 保留合成前台测试 App、fixture server、driver/calibrate |
| `work/task-move-probe`、`work/mirror-probe` | 保留为设备诊断工具 |
| `work/parcel-failure-check`、`work/frame-memory-probe`、`work/frame-capture-check` | 保留为协议/内存/截图故障注入 |
| `work/focus-20261016`、`work/clipboard-20261016` | 保留为焦点/输入/剪贴板诊断 |
| `work/qualification/aosp` | 保留为 API 参考，不进入产品代码 |

## 4. 必须重写

| 资产 | 原因 |
|---|---|
| `engine/AgentLoop.kt` | 单步 observe→act；无技能/记忆/重规划/效果验证；新 loop 的核心替代对象 |
| `engine/TaskPlan.kt`、`plan/TaskPlanner.kt` | 固定 1–4 段/固定计划；新设计是 TaskGraph + 技能 + 重规划 |
| `engine/TaskRuntime.kt` | 旧 pause/resume/handoff 状态机与旧 loop 耦合；保留接口语义，内部重写 |
| `ui/MainActivity.kt` 任务区/预设 | 旧条件、预设、阶段、预算、确认逻辑；新 UI 只保留自然语言 + 契约 + 接管 |
| `model` 的 action prompt/schema | 旧 action 列表/单步 JSON；新协议是技能/意图/预期效果/风险/证据 |
| `core/TaskContract.java`、`ExecutionGate.java`、`ActionBudget.java` | 设计参考，不直接复制：旧契约把 completionCriteria 设为必填、预算 50/480，和新自然语言/动态预算不一致 |
| `engine/TaskBudget.kt` | 旧 10/16 动作预算；新 loop 动态预算 + 硬上限 |
| `preview` 的 500ms 截图轮询 | 已被 display mirror 替代；只保留视频/触摸 |

## 5. 归档/不作为规范

这些可以保留在旧 repo/证据目录，但**不要带进新项目的设计基线**：

- 固定 Quark→JD 两段链、JD 搜索/加购/购物车预设；
- 旧“完成条件”文本、逗号分隔、1–8 条、80 字限制；
- 旧 Planner 的 1–4 段/固定 required 提示；
- 旧 AgentLoop 的 10/8、16/12 预算和“一观察一动作”；
- 旧 `TaskContract` 的 50/480 预算（作为历史参考可以）；
- 旧 docs 中“已实现/计划中”的过时表述；
- 旧 APK、`work` 全量原始日志/截图（含 PII/设备序列号）。

## 6. 测试软件与场景验证：保留什么

### 6.1 合成前台测试 App

路径：`work/foreground-20261016/program/`

内容：`TestActivity`、`StateProvider`、`AndroidManifest.xml`、`build.sh`、`driver.py`、`fixture_server.py`、`calibrate.py`、`test_driver.py`。

价值：验证 Agent 后台运行时，主屏按键/文字/滚动/焦点/输入法不被影响。A18 长测 1219.92 秒、主屏 148 次按键检查、副屏 22 轮操作。

处理：**保留到新项目 `testkit/foreground`**，改为新包名/新 target 也可；它是测试 App，不是产品代码。

### 6.2 准入/输入/帧探针

路径：`tools/qualification/`

内容：InputRequest/FrameRequest/TouchRequest/ObservationBudget/SessionPolicy 及测试。

价值：输入/帧/预算/会话边界的独立验证，不依赖主 App。

处理：**保留到 `testkit/qualification`**，扩展为新 capability API 的回归。

### 6.3 任务移动/镜像/故障探针

- `work/task-move-probe`：隐藏 `ActivityTaskManager` root task 移动；主屏接管验证。
- `work/mirror-probe`：`VirtualDisplayConfig.Builder` 方法探测；mirror 前置验证。
- `work/parcel-failure-check`：真实 Parcel/SharedMemory 失败注入。
- `work/frame-memory-probe`、`work/frame-capture-check`：截图/内存/FD 诊断。
- `work/focus-20261016`、`work/clipboard-20261016`：焦点/无障碍树/输入诊断。

处理：**保留为 `testkit/probes` / `testkit/diagnostics`**；不进入产品运行时。

### 6.4 Mock / 设备脚本

- `work/model-loop/mock-*.json`、`*_setup.py`、`planner_setup.py`、`chain_setup.py`、`jd_add_setup.py`
- `work/model-loop/PreviewStore` 证据截图

处理：**适配**。mock action schema 随新 loop 重写；setup 脚本用于快速布景，不作为验收。

### 6.5 场景验证证据（保留为事实，不作为新 loop 通过）

| 证据 | 结论 | 新项目用途 |
|---|---|---|
| Quark 单阶段真实任务 | verified=true | capability/旧 loop 参考 |
| Quark→JD 两段链 | verified=true（预设） | 跨 App capability 参考 |
| Planner 3 段同 App | verified=true（旧 Planner） | 旧 loop 参考 |
| JD 加购 | **BLOCKED**（JDRiskHandleActivity） | 必须重新验收；不要写成成功 |
| 主屏接管 | mock 122→0→122 | capability 回归 |
| display mirror 视频 | mock 视频 + agent 并发 | capability 回归 |
| 预览触摸接管 | mock tap → JD BabelActivity；关闭恢复 | capability 回归 |
| 38 次采样 / Parcel 失败 / 5 轮重绑 | PASS | 协议/生命周期回归 |
| A18 合成并发 1219.92s | PASS | 非干扰回归 |
| 5 轮 JD 浏览 | 历史证据 | 真实 App 回归参考 |

这些是“设备身体”的证据，不是新 TaskGraph/Skill/风险门/接管策略的验收。

## 7. 建议的新项目结构

```text
agent-next/
  capability/        # Android module: AIDL, Shizuku, display/target, observation/frame,
                     # input, preview mirror, task move, policies
  brain/             # pure JVM/Kotlin: TaskSpec, TaskGraph, skills, memory,
                     # slow/fast loop, risk gate, verifier interfaces
  app/               # UI, foreground service, preview, human gateway
  testkit/
    foreground/      # synthetic foreground test app + fixture server + driver
    qualification/   # input/frame/touch/session probes
    probes/          # task move, mirror, parcel failure, frame memory
    harness/         # device scripts, mock model, scenario fixtures
  docs/
    loop-redesign.md
    device-capability.md
    validation.md
```

依赖方向：`app → brain → device-api`；`capability` 实现 `device-api`；`brain` 不依赖 Android UI。保留 AIDL 作为 `device-api` 的边界，不把 shell 细节暴露给 brain。

> 模块名沿用现有 `capability/`，不重命名为 `device/`；`device-api` 指其对外接口边界（D5）。

## 8. 复制清单（建议）

### 直接复制

```text
app/src/main/aidl/agent/phone/ipc/IAgentShell.aidl
app/src/main/java/agent/phone/connection/*.java
app/src/main/java/agent/phone/executor/*.java
app/src/test/java/agent/phone/connection/*Test.java
app/src/test/java/agent/phone/executor/*Test.java
tools/test-service.sh
tools/test-shizuku-adapter.sh
tools/qualification/**
work/foreground-20261016/program/**
work/task-move-probe/**
work/mirror-probe/**
work/parcel-failure-check/**
work/frame-memory-probe/**
work/frame-capture-check/**
```

### 适配后复制

```text
app/src/main/java/agent/phone/model/VisionModel.kt
app/src/main/java/agent/phone/model/ScriptedVisionModel.kt
app/src/main/java/agent/phone/ocr/OcrEngine.kt
app/src/main/java/agent/phone/engine/TaskResult.kt
app/src/main/java/agent/phone/engine/TaskRunStore.kt
app/src/main/java/agent/phone/engine/CompletionNotifier.kt
app/src/main/java/agent/phone/core/CompletionVerifier.java
app/src/main/java/agent/phone/core/CartVerifier.java
app/src/main/java/agent/phone/core/CartExpectation.java
app/src/main/java/agent/phone/service/AgentTaskService.kt
app/src/main/java/agent/phone/preview/PreviewActivity.kt
app/src/main/java/agent/phone/ui/MainActivity.kt   # 只取连接/目标/App 选择
work/model-loop/*_setup.py
work/model-loop/mock-*.json
```

### 不复制

```text
private/**
work/signing/**
work/**/raw device logs/screenshots with PII
app/build/**
.gradle/**
local.properties
model API keys / model-config.json
旧 APK/第三方 APK（按官方依赖重建）
```

## 9. Clean-room 规则

1. 新 repo 以 `docs/loop-redesign.md` 为规范，不以旧 `AgentLoop`/`TaskPlanner` 为模板。
2. 旧 repo 冻结为**证据/参考**，新 repo 不通过 submodule/源码依赖引用旧 brain。
3. `device` 只暴露 capability API；brain 不能直接 import Android `ShellService`/AIDL 实现。
4. 旧 loop 的成功/失败记录只作为 capability 回归，不作为新 loop 验收。
5. 先让 device capability 的 host + 设备回归过，再写新 brain。
6. 所有场景测试先写“预期结果/人工介入边界”，再写实现。
7. 不把 JD 作为默认任务或唯一成功标准。

## 10. 迁移顺序

1. **冻结旧仓库**：记录 commit、设备/依赖版本；不再加旧 loop 功能。
2. **复制 capability + testkit**：先跑 host 三套、合成前台 App、display/target/observation/input 设备回归。
3. **定义 device-api**：AIDL/接口、能力清单、错误/超时/清理语义；旧实现可作为参考实现。
4. **新 brain 骨架**：TaskSpec/TaskGraph/Skill/Memory/EffectVerifier/RiskGate/HumanGateway，先写 host 测试。
5. **新 UI 最小闭环**：自然语言 + 任务图确认 + 预览/接管 + 结果。
6. **Chain A 非购物**：Quark 天气 → 日历；验证技能/记忆/重规划/verifier。
7. **Chain B 购物**：Quark → JD 加购/购物车；人处理安全验证；支付前停。
8. **故障/20 分钟/手机独立**：按新边界验收。

## 11. 风险

- 旧 capability 的 bug 会带进新项目：用现有 host/设备回归先锁住。
- `device` 与 `brain` 边界不清会重新长成旧 loop：brain 不得直接调用 shell/坐标。
- 测试工具依赖旧 UI/包名：先做 adapter，不要复制旧 UI。
- 原始 work 证据含 PII/设备信息：只保留去标识摘要/脚本。
- 新 loop 的“技能”容易退化成固定点击脚本：技能必须声明前置/后置条件与回退，不接受固定坐标序列。
- 旧 JD 失败被误读为“新 loop 也应能自动完成”：安全验证仍需人。

## 12. 待确认（已定）

2026-10-19 已定稿，权威记录见 [decisions.md](decisions.md)：

- D5：单一模块化 monorepo（`agent-next`）；旧仓库只读冻结，不保留维护分支。
- D6：保留主屏接管，作为预览触摸之外的显式 fallback。
- D1：默认自主级别 A3。
- D7：文字输入优先 `setText`；不做自有 IME，IME 场景走主屏接管。
- D8：testkit 随仓库内部保留，不随产品发布。

---

相关：[Loop 重设计](loop-redesign.md)
