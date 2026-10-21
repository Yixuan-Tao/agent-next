# 目标 App 音频隔离：实现与设备验证记录

更新：2026-10-21。状态：**Android 16 平板上完成实现、对抗性复核修复与真实设备路由复验；这是单设备工程证据，不等于 Beta 验收通过。**

本记录对应未提交工作区（基线 `b242ee6` + 未提交音频隔离改动），设备产物为本机重新签名的 Debug APK。历史 APK、历史三次任务成功记录与本轮音频改动无关，不能作为本轮通过依据。

## 目标与契约

- Agent 在私有虚拟屏操作目标 App 时，**该 App 默认静音**，声音不送到扬声器。
- 只有用户明确接管时恢复目标 App 声音：交互预览接管（`PreviewActivity`）或显式移到主屏接管。
- 电脑只读监看（`DemoFramePublisher`）**不恢复声音**。
- 交还 Agent 后重新静音；交还动作必须以“确认已重新静音”为前提，未确认则保持 Agent 暂停。
- 不修改全局音量、主屏 App 声音或系统静音状态；匹配范围只有目标 UID。
- 无法建立隔离时**阻止目标启动**，不允许无声策略静默降级。
- 音频只在本地短暂缓冲后丢弃，不保存、不上传、不写日志内容。

## 实现摘要

| 位置 | 职责 |
|---|---|
| `capability/.../TargetAudioIsolation.java` | 纯 Java 所有权状态机：bind 后 muted；preview/main 任一用户控制为 audible；都退出则重新 muted；创建/关闭失败不提交状态，释放不确定时保留所有权并允许重试；释放成功后复位所有权。 |
| `capability/.../AndroidTargetAudio.java` | 以 shell（uid 2000）注册仅匹配目标 UID 的 `AudioPolicy`：`AudioMixingRule.RULE_MATCH_UID` + `AudioMix.ROUTE_FLAG_LOOP_BACK`；用 `createAudioRecordSink` 建立丢弃缓冲并持续读取/清零。关闭时逐步尝试 stop / join / release / unregister 并聚合异常，避免一次残留跳过策略注销；归属补丁在旧 ROM 上不可用时回退为直接建立，由真实结果决定成功或失败。 |
| `TargetLifecycle` | 打开目标前 `audio.bind(uid)`；移动目标前后更新主屏用户控制，移动失败时补偿恢复或标记需要清理；目标被用户切到前台而拒绝操作时把声音交还用户；关闭目标时释放策略。 |
| `ShellService` + AIDL | 镜像 channel 分为交互接管、手机只读、电脑只读；只有交互接管恢复目标音频，两个只读画面各自独立停止。shutdown/destroy/release-display 逐步清理资源，确保音频策略在所有失败路径上仍被尝试释放。 |
| `BlockingDeviceApi` / `PreviewRpc` / `ConnectionCoordinator` | 区分只读镜像与交互接管；镜像 RPC 串行、超时补偿；开始镜像失败时主动尝试一次 stop 清理“可能已远程启动但本地未知”的镜像。 |
| `AgentRuntime` / `PreviewActivity` / `AgentTaskService` | 用户关闭预览或通过 handoff 通知交还控制时，先等待服务确认镜像停止且重新静音，成功后才恢复 Agent；未确认则保持暂停并提示用户。handoff 恢复前也先停止交互镜像。 |
| Debug `ShellActivity` | 新增 `mode=audio` 诊断：bind → 只读镜像 → 交互接管开/关 → 移主屏 → 回私有屏 → 释放；并在 app 进程内播放 880 Hz“旁观者”音轨用于对照。 |

技术依据：Android 16 `AudioPolicy`/`AudioMix` 的 loopback 策略会把匹配 UID 的播放路由到策略专属 remote-submix 输出，再由 `createAudioRecordSink` 读取丢弃；`unregisterAudioPolicy` 后播放回到普通输出。注册需要 shell 权限（`MODIFY_AUDIO_ROUTING`/`MANAGE_AUDIO_POLICY` 已授予 `com.android.shell`）。

**关键兼容修复**：AudioFlinger 在 Android 16 校验 trusted uid（uid 2000）的归属，最初策略 record 因应用上下文把包名报成 `agent.next.app` 而被 `createFromTrustedUidNoPackage` 判为 `EX_SECURITY`/`invalid attr`。修复方式是在创建策略 sink 前后临时把当前应用 `ContextImpl.mAttributionSource` 换成进程实际所属的 `com.android.shell`，创建完成立即恢复（`finally` 保证）。此路径依赖隐藏 API 反射，属于已知的 ROM/版本风险；补丁不可用时回退为直接建立，最终仍失败则按契约阻止任务启动。该临时改写影响进程级归属，窗口极短但理论上会波及同进程并发调用，已记录为待改用专用 attributed context 的后续工作。

## Host 检查

```sh
QUAL_JAVA_HOME="$JAVA_HOME" sh tools/verify-host.sh
./gradlew :app:assembleDebug :app:assembleRelease --offline
```

结果：`verify-host.sh` 全量 PASS（service/policy、device-api、brain 26 suites、capability adapter、Shizuku adapter、files GUI、known defects），Debug/Release 构建与 manifest 边界检查通过。新增/扩充回归：

- `TargetAudioIsolationTest`：默认静音、只读静音、接管恢复、回交再静音、同 UID 重复 bind 幂等、主屏恢复失败保留所有权、释放失败后可重试并复位所有权。
- `TargetLifecycleTest`：隔离失败阻止启动；绑定后用户把目标切到前台时把声音交还用户；close 不重复释放。
- `PreviewRpcTest` / `ConnectionCoordinatorTest` / `ShizukuBackendTest`：镜像 RPC 串行与不确定状态、协议 4、user-service v14 包结构。

这些只证明 host 状态机/协议，不证明真实系统音频行为。

## 设备与安装

- 设备：Redmi Pad Pro，型号 `2405CRPFDC`，Android 16 / API 36，构建 `BP2A.250605.031.A3`；ADB 无线调试；Shizuku user service 运行 uid 2000（`com.android.shell`）。
- 设备已有 `agent.next.app` 是同一 release keystore 签名的 Debug 构建。本轮把新 Debug APK 用本地私有 release keystore 重新签名后 `adb install -r` 覆盖，保持同签名与 app 数据（加密 model config / episodic 仍在）；没有卸载清数据。
- 最终设备包 SHA-256：`fa1a81e9c8b65633327ee89cebd4b99678330661e9525547d1ac630711c926ab`（本机签名产物，不入库）。修复前首轮验证包为 `a8f6…`；正文时间线取自最终包。
- MIUI/HyperOS 拒绝通过 ADB 安装全新包（`INSTALL_FAILED_USER_RESTRICTED`），因此对照音轨没有新建包：目标音轨使用既有测试包 `agent.foreground.test` 的同签名更新版（`agent.foreground.test-tone`，SHA-256 `e960b2…`），旁观音轨由 `ShellActivity` 在 `agent.next.app` 进程内播放（uid 10268）。验证结束后已把 `agent.foreground.test` 恢复为原 APK。该限制也说明真实演示设备需要提前处理安装权限。

## 验证过程

```sh
# 1. 目标 App 播放 440 Hz 媒体音轨（测试包）
adb shell am start -n agent.foreground.test/.ToneActivity --ei freq 440
# 2. 运行音频隔离诊断（含 app 进程内 880 Hz 旁观音轨）
adb shell am start -n agent.next.app/.ShellActivity --es mode audio --es package agent.foreground.test
adb logcat -s AgentNextSmoke:I AgentShellAudio:I AudioToneProbe:I
```

验证期间每约 1 秒抓取 `dumpsys audio` / `dumpsys media.audio_flinger` / `dumpsys media.audio_policy`；最终包原始摘录在 `work/audio-probe/evidence/`（本地验收材料，不入库）。

### 路由与策略时间线（最终包，2026-10-21 01:22–01:23）

| 时间 | 阶段（探针标记） | AudioService 策略事件 | 目标 UID 10317 播放路由 | 旁观 UID 10268 路由 |
|---|---|---|---|---|
| 01:22:50 前 | 目标 App 在普通屏幕播放（基线） | 无 | `deviceIds:[2]`（扬声器） | — |
| 01:22:51.409 | `bystander_tone_on` | 无 | `[2]` | `[2]`（扬声器） |
| 01:22:51.524 | `target_open`（应为静音） | `registerAudioPolicy ... u/pid:2000/27219 ... Mix:0-Typ:p-Rul:1`（`ap:13`） | **`[226]`** = 当次策略专属 “Remote Submix Out” | `[2]` |
| 01:22:51.602 | 静音 sink 运行 | `rec update riid:775 uid:2000 session:889 src:REMOTE_SUBMIX not silenced` | `[226]` | `[2]` |
| 01:22:54.6–56.6 | `read_only_on/off`（电脑只读，应保持静音） | 无变化 | `[226]` | `[2]` |
| 01:22:58.454 | `takeover_on`（用户接管，应恢复） | `unregisterAudioPolicy` | `[2]`（扬声器） | `[2]` |
| 01:23:01.033 | `takeover_off`（交还，应静音） | `registerAudioPolicy ... ap:14` | **`[243]`** = “Remote Submix Out” | `[2]` |
| 01:23:03.580 | `main_screen`（移到主屏由用户控制，应恢复） | `unregisterAudioPolicy` | `[2]` | `[2]` |
| 01:23:06.121 | `private_screen`（回到私有屏，应静音） | `registerAudioPolicy ... ap:15` | **`[260]`** = “Remote Submix Out” | `[2]` |
| 01:23:08.478–08.902 | `target_released`（客户端释放）→ display release（远端清理） | `unregisterAudioPolicy` | 播放器结束 | `[2]`，随后停止 |

结果：

- 目标 UID 每次静音都从扬声器（device 2）切换到“当次策略专属”的 Remote Submix Out（226/243/260 分别对应 `ap:13/14/15`），策略注销后立即回到 device 2。目标音轨一直 `state:started`，但输出目标不是扬声器，且 shell sink 在读取丢弃。
- 旁观 UID 在所有样本中始终 `deviceIds:[2]`，没有被改写路由；未发现其他 UID 被策略重路由。
- `AudioPlaybackConfiguration` 中只有目标 UID（10317）出现在非扬声器设备上，与策略规则数 `Rul:1` 一致。
- 全局音量与静音未变化：静音/接管阶段与基线比较，`STREAM_MUSIC streamVolume:5 / Muted:false`、`STREAM_ALARM:12`、`STREAM_RING:10`，`Devices: speaker(2)`。

### 真实第三方 App 绑定

同一探针把目标换成真实第三方 App 夸克（`com.quark.browser`，uid 10312）重跑，连接、私有屏、目标打开、观察、只读镜像、接管开关、主屏/私有屏往返、释放全部 PASS；日志出现 `target audio isolated uid=10312` 与对应 restore，策略注册/注销成对出现（`ap:17`/`ap:18`）。这证明真实 App 能在隔离策略下正常启动和操作，但该轮没有让夸克播放音频，不能替代真实播放内容的静音验证。

### 失败即阻止启动（fail-closed）

修复前（同设备、同链路）策略注册本身成功，但 `createAudioRecordSink` 被 AudioFlinger 拒绝：

```text
E AudioFlinger: ... createRecord ... EX_SECURITY: '... createFromTrustedUidNoPackage(AttributionSourceState{... uid: 2000, packageName: agent.next.app ...}): invalid attr ...'
W AgentShellService: java.lang.IllegalStateException: Silent audio sink could not be initialized
E AgentNextSmoke: agent.next.device.DeviceException: launch target failed: ERROR/MALFORMED
```

表现为 `COMPAT FAIL`，目标没有继续启动；修复后才 `COMPAT PASS` 并出现 `target audio isolated uid=...`。保留的过滤日志见 `work/audio-probe/evidence/fail-closed-compat-2026-10-21.log`（`AudioFlinger` 原始行来自当时完整 logcat，未单独留存）。这说明隔离建立失败时不会静默放行（与 host 状态机回归一致）。

## 对抗性复核后的修复

对实现做过一次独立只读复核，发现并修复了以下问题；host 全量回归通过后，最终包重新做了上面的设备复验：

| 问题 | 修复 |
|---|---|
| handoff/通知“继续”可能在交互预览仍处于用户接管时恢复 Agent，导致 Agent 恢复后目标仍有声 | `AgentRuntime.handoffToUser` 在恢复前先 `stopPreview` 并确认，失败则 handoff 失败、保持暂停 |
| `PreviewActivity.onDestroy` 异步 `stopPreview` 后立即 `resumeAfterUser`，未确认重新静音 | 改为 stopPreview 回调确认成功后才 resume；未确认时保持暂停并提示 |
| 镜像 start 超时且补偿 stop 失败后，客户端可能永远无法恢复镜像状态 | `BlockingDeviceApi` 在 start 失败时主动尝试一次 stop 清理不确定远程状态 |
| 移到主屏/回私有屏失败时音频状态可能与实际显示位置不一致 | 移动失败补偿恢复或标记 invalid，阻止继续操作；主屏恢复失败要求清理 |
| 绑定后目标被用户切到前台而拒绝操作时，目标仍是静音 | `refused` 路径在返回冲突前把声音交还用户（`audio.mainScreen(true)`） |
| 归属补丁反射失败会直接使所有任务无法启动 | 补丁改为 best-effort，不可用时回退为直接建立 sink，由真实结果决定 |
| `AndroidTargetAudio.close` 在 stop/join 失败时可能跳过 release/unregister，长期保持静音 | 改为每步独立尝试并聚合异常；`TargetAudioIsolation.close` 在 lease 失败后仍尝试 backend close，确认清理成功才复位所有权 |
| 旧 code 的 Shizuku user-service 进程可能在 APK 覆盖升级后继续存活，协议不匹配被误分类 | user-service 版本/标签升级到 `14` / `agent.next.connection.v14`，强制重建进程 |

复核同时指出：`PreviewRpc` 的“不确定镜像”仅能通过后续 stop 或再次 start 的超时补偿恢复；完整恢复仍要求重建会话，已计入边界。app 层 handoff/resume 门控属于代码级修复 + 编译/host 回归，**没有在真机上跑完整模型任务的 handoff 流程**，需要后续设备回归补证。

## 验证矩阵

| 项目 | 结果 | 证据 |
|---|---|---|
| Agent 后台执行默认静音 | PASS（目标路由离开扬声器到丢弃 sink） | 01:22:51.524 注册 + `[226]` |
| 电脑只读监看不恢复声音 | PASS（read-only 开关不改变策略） | 01:22:54–56 无 unregister，目标仍 `[226]` |
| 用户交互接管恢复声音 | PASS（注销策略后回到 `[2]`） | 01:22:58.454 |
| 交还 Agent 后重新静音 | PASS（新策略、新 remote-submix 端口） | 01:23:01.033 + `[243]` |
| 移主屏用户控制恢复 | PASS | 01:23:03.580 + `[2]` |
| 移回私有屏重新静音 | PASS | 01:23:06.121 + `[260]` |
| 停止/释放清理 | PASS（注销、播放器结束、sink 停止、无遗留策略） | 01:23:08.884；清理后无 active player / remote-submix 线程 |
| 不误静音其他 App | PASS（旁观 UID 全程 `[2]`；策略 `Rul:1`） | 全部样本 |
| 不改全局音量/静音 | PASS（基线=静音/接管阶段音量表） | s1 vs s3/s8 |
| 隔离失败时阻止启动 | PASS（修复前 `COMPAT FAIL`，未启动目标） | 失败日志 |
| 不保存/上传音频 | 代码审查（只读丢弃、清零；无音频日志/文件） | `AndroidTargetAudio.drain()` |
| app 未申请麦克风权限 | PASS（manifest 未新增 `RECORD_AUDIO`；录制发生在 shell uid） | `app/src/main/AndroidManifest.xml` |
| handoff/resume 先确认静音 | 代码级修复 + 编译/回归；未完成真机完整任务验证 | `AgentRuntime` / `PreviewActivity` |

## 边界与未验证项

- **按 UID 隔离，不等于按 display/窗口隔离。** 同一 App UID 的多个窗口或后台播放会一起被静音；无法只静音私有屏里那个窗口。
- 目标 App 的同 UID 声音全部受影响，包括其通知音、广告音、后台播放；这是本轮契约的选择，不是“只静音界面音效”。
- 只验证了 `AudioTrack`/`USAGE_MEDIA` 路径（440 Hz 合成音）。硬件 offload、AAudio、SoundPool、通话/语音、DRM 独占输出等路径未验证，不能宣称全覆盖。
- “静音”是通过系统路由证据（目标输出从 speaker 改为当次策略的 Remote Submix Out 且 sink 丢弃）证明的，不是声学测量；没有用外部麦克风录扬声器做二次确认。
- 依赖隐藏 API 反射（`AudioPolicy`/`AudioMix`/`AudioRecord`、`ContextImpl.mAttributionSource`）和 Shizuku shell 权限；其他 ROM/Android 版本可能拒绝注册或校验失败。失败时按契约阻止任务启动，但尚未在第二台设备/API 33 上验证。API 33 上归属补丁可能不适用，回退路径的结果未实测。
- 音频焦点、设备切换（耳机/蓝牙）、来电/闹钟打断未纳入本轮；策略只做 UID 路由，不改变目标 App 的音频焦点状态。
- 目标 App 在“移主屏”时恢复声音，是当前产品语义（主屏 = 用户可见/可控制）；如果未来 Agent 在非接管情况下把目标移到主屏，需要重新确认该语义。
- 只验证了“用户主动接管”和“主屏”两种恢复入口；产品文案需要明确告诉用户：电脑监看听不到声音是预期行为。
- app 层 handoff/resume 门控、镜像不确定状态恢复与资源清理加固是复核驱动修复，host 回归通过但未在真机模型任务中验证端到端行为。
- 本轮是单设备单 App 的工程证据；历史三次任务成功属于旧 APK，不是本轮音频版本通过记录。

## 复现与清理

1. 构建 Debug APK，用本机 `private/` release keystore 重新签名后覆盖安装（同签名，不卸载）。
2. 构建 `agent.foreground.test` 同签名音轨测试包（源码模板在 `work/audio-probe/`，因 MIUI 新包限制而复用既有测试包），`adb install -r` 更新。
3. 启动目标音轨与 `ShellActivity --es mode audio`，抓取 logcat + 三份 dumpsys；对照上表的时间线。
4. 完成后：`am force-stop agent.foreground.test`、用原始 APK 覆盖回滚测试包、`am force-stop agent.next.app`；确认无 active player / Remote Submix 线程与未注销策略，不保留音频内容或额外录音。测试音轨源码/构建脚本与原始 dump 只留在本地 `work/`，不进入产品仓库。

本轮没有产生可发布的验收结论；后续仍需第二台设备（API 33）、真实第三方 App 的播放内容、耳机/蓝牙与音频焦点场景、app handoff 全流程的设备验证。
