# capability 契约（新项目）

状态：从旧仓库 `agent-phone` 复制并通过 host 回归；尚未接入新 brain/app。

## 模块

- Gradle module：`:capability`（Android library，namespace `agent.phone.capability`）
- Java package：暂保留 `agent.phone.connection` / `agent.phone.executor`，降低复制风险；新 app/brain 的包名后续独立。
- 依赖：`dev.rikka.shizuku:api:13.1.5`、`dev.rikka.shizuku:provider:13.1.5`；没有 ML Kit/UI/模型依赖。
- 目标：Android 16 / API 36、shell UID 2000、普通 App UID。

## Binder 边界

`IAgentShell` 当前（协议 11 / tag v9）暴露：

| ID | 方法 | 作用 |
|---|---|---|
| 0 | `getCapabilities` | 协议/API/UID/能力 |
| 1–3 | display create/check/release | 私有 display 生命周期 |
| 4–5 | target launch/observe | 目标 App 启动/观察 |
| 6 | `bindTarget` | 授权包/目标绑定 |
| 7 | `captureTarget` | 单帧证据 |
| 8–11 | tap/setText/swipe/back | 定向输入 |
| 12 | `moveTargetToDisplay` | 主屏 task move（用户接管） |
| 13–14 | mirror start/stop | 私有 display → app Surface 视频 |
| 15–16 | userTap/userSwipe | 用户接管触摸注入 |
| destroy | Shizuku oneway | 服务清理 |

新项目可以重新编号协议，但应保留以下语义：

- 客户端不能传 UID/Activity/Intent/路径/任意 displayId；
- display/target 凭证由服务签发，绑定 generation/uniqueId；
- 后台只接受非零私有 display，拒绝 display 0；
- 每次动作绑定目标/窗口/观察，旧观察/旧凭证拒绝；
- 一时刻一个 target/observer/display；
- 输入按 display + target UID 定向；
- 预览镜像不走 Agent capture/RPC；
- 主屏接管是用户主动交接。

## 关键不变量

- `ClientPolicy`/`InstalledAppIdentity`：只接受安装 App 的 Binder 调用者；服务清理后可拒绝。
- `DisplayLifecycle`：单资源所有权、token/generation、粘性失效、清理确认；display 0 拒绝。
- `TargetLifecycle`：单 target/observer；窗口/显示/UID/前台冲突校验；旧观察失效。
- `ObservationBudget`/`FrameLease`：节点/内容/包大小限制、单帧所有权、失败清理。
- `FrameSafetyPolicy`：owned/unrooted 窗口例外、焦点/层变化、黑帧/尺寸拒绝。
- `AndroidInputDispatcher`：定向 tap/swipe/key；不借用全局输入法/剪贴板。
- `PreviewMirror`：shellContext + DisplayManager(Context) + `com.android.shell` + `setDisplayIdToMirror` + **仅 AUTO_MIRROR**；SurfaceView/TextureView 由 app 提供。
- `TargetTaskMover`：隐藏 `ActivityTaskManager` root task move；只允许 display 0 或当前私有 display。
- `ServicePolicy`/`PrimaryTaskPolicy`：单任务/主屏争用/失败清理。

## 构建/测试

```sh
JAVA_HOME=<jdk21> GRADLE_USER_HOME=<cache> ./gradlew --offline :capability:assembleDebug
QUAL_JAVA_HOME=<jdk21> sh tools/test-service.sh
GRADLE_USER_HOME=<cache> QUAL_JAVA_HOME=<jdk21> sh tools/test-shizuku-adapter.sh
```

当前结果：

- library build PASS；
- service/display/target/observation/frame/packet host tests PASS；
- Shizuku 13.1.5 adapter PASS；
- 纯 JVM `device-api` 首版已定义（`FakeDeviceApi` 契约 PASS），capability adapter 尚未实现；
- 设备首轮回归（2026-10-19，Redmi Pad Pro / API 36）：testkit shell 诊断的 display/observe/frame/input/touch/task-move/frame-memory PASS；真实 mirror/touch takeover 与 1200s 并发未覆盖。详见 [device-regression-2026-10-19.md](device-regression-2026-10-19.md)。

## 不在 capability 内

- 任务规划/技能/记忆/风险门/完成判定（新 brain）；
- 自然语言 UI、确认卡、文字/IME 接管 UI；
- 模型调用、OCR、购物车/日历等领域 verifier（可作为独立插件）；
- 真实 JD/日历等新 loop 验收。

## 设备回归前提

复制源仓库的 device/testkit 工具（`testkit/`）先跑：

1. 空白 display 生命周期 + 主屏焦点；
2. 真实目标观察/单帧 + 38 次采样/Parcel 失败/重绑；
3. 定向输入/中文文本；
4. preview mirror + 触摸接管；
5. 主屏 task move；
6. 合成前台 A18 并发长测。

这些通过后才接新 brain；旧 loop 的成功记录不作为新 loop 证据。
