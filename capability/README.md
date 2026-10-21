# capability（Android 设备身体）

Android library：AIDL、Shizuku 连接、私有 display、target 会话、观察/帧、定向输入、preview mirror、主屏 task move、目标 UID 音频隔离、安全/协议策略。

本模块是 [纯 GUI 内部 Beta](../docs/pure-gui-beta.md) 的设备执行底座。业务结果必须由上层策略通过 GUI 完成，不在此加入日历、文件、闹钟等直接业务写入捷径。现有能力与设备 smoke 不是整个 Beta 已验收的证明。

## 目标职责

- 坐标、Surface、AIDL 和 shell 细节留在受信任设备适配层；skill 不直接获得这些权限。
- 输入必须绑定有效 target 和观察，旧句柄、旧观察、非目标窗口和主屏 agent 输入继续拒绝。
- 上层统一执行器负责语义风险、确认、取消与预算；设备层身份校验不能替代业务风险判断。
- 目标 Beta 要求用户与 agent 输入互斥，以及 Android 13/16 完整设备回归；镜像不可用时显式降级，不把能力声明当作实测结果。

## device-api adapter

`agent.phone.capability.BlockingDeviceApi` 把 `ConnectionCoordinator` 的异步状态机包成同步 `DeviceApi`；`agent.phone.connection.ShizukuConnectionDriver` 负责 Android/Shizuku 绑定。

```java
BlockingDeviceApi api = new BlockingDeviceApi(
        listener -> new ShizukuConnectionDriver(context, listener));
api.connect();
// api.displays(), api.targets(), api.observations(), api.input(), api.tasks()
api.close();
```

使用约束：

- 在 **worker thread** 调用；每个方法会阻塞等待协调器状态或超时（默认 15s，协调器自身 deadline 10s）。
- 同一时刻只允许一个调用方/一个 target；`DeviceApi` 不是多线程调度器。
- `TargetController.release` 通过 connection driver 发起 target 释放并等待绑定清理；display 资源需由拥有者显式释放。跨 App 重绑与故障清理需要设备回归覆盖。
- `Observation.activityName` 目前为空：capability 的 observation 还没有当前 Activity 字段；`TargetInfo.activityName` 是启动 Activity。
- `InputReceipt.VERIFIED` 表示服务端文本回执/回读路径被接受；adapter 不额外再读字段。
- `TargetInfo.foreground` 目前恒为 `true`；`Frame.capturedAt` 是 adapter 接收时间，序列/尺寸来自服务端证据。
- preview mirror 和用户触摸注入通过 capability-only `AndroidPreviewController` 暴露（`BlockingDeviceApi` 实现）；`device-api` 仍不接收 Android `Surface`。
- host adapter 覆盖 start/stop preview 和 userTap/userSwipe 参数；Android 16 上已有 [镜像/触摸记录](../docs/preview-touch-smoke-2026-10-19.md)，其他设备与完整中断恢复仍需分别验收。

## 目标 UID 音频隔离

target 打开时，`TargetLifecycle` 先调用 `TargetAudioIsolation.bind(uid)` 建立静音；策略和 target 生命周期同源，任何失败都先于目标启动抛出。

- 静音：`AndroidTargetAudio` 以 shell uid 注册 `AudioPolicy`，规则只匹配目标 UID（loopback mix），用 `AudioRecord` 读取策略 sink 并清零丢弃；不修改全局音量、系统静音或 AppOps。
- 用户控制：`preview`（交互接管）或 `mainScreen`（目标移到主屏由用户控制）为 true 时释放策略、恢复声音；两者都退出时重新注册静音。只读监看始终传 false。
- 协议：镜像按交互接管、手机只读、电脑只读三个 channel 独立创建和停止，关闭一个查看端不会影响另一个。只有交互接管会恢复目标音频；只读监看保持原音频策略。user-service 版本/标签为 `15` / `agent.next.connection.v15`，协议版本同步升级以强制重建旧进程。
- 失败：注册/record/启动任一失败则目标启动失败；release display 会逐步尝试 frames/target/display 清理，音频策略不会被前面的清理失败跳过；关闭时逐步尝试 stop/release/unregister 并聚合异常，释放不确定时保留所有权、清理可重试。移动目标失败会补偿音频状态或标记需要清理；目标被用户切到前台而拒绝操作时把声音交还用户。
- 镜像不确定状态：start 超时且补偿失败时，后续 start 失败路径会再尝试一次 stop 清理；完整恢复仍建议重建会话。
- 已知边界：按 UID 而非窗口/display 隔离；同 UID 的其他窗口、通知音和后台播放会一起静音。依赖 Android 隐藏音频 API、shell 权限和特定 AudioFlinger 归属校验（Android 16 需要让 sink 以 `com.android.shell` 归属创建；反射补丁不可用时回退为直接创建，由系统结果决定）。仅在 Redmi Pad Pro / Android 16 上验证过 `AudioTrack` 媒体路径，其他 ROM/API、真实 App 播放内容、耳机/蓝牙、硬件 offload 与音频焦点未验证。app 层“关闭预览/handoff 继续前确认静音”为代码级修复，真机完整任务 handoff 未回归。
- 证据与限制：[目标 App 音频隔离验证记录](../docs/device-audio-isolation-2026-10-21.md)。

## host 契约

```sh
JAVA_HOME=/path/to/jdk21 sh tools/test-capability-adapter.sh
```

脚本用 `FakeConnectionDriver` 在 host 上跑 `DeviceApiContract`，不依赖 Android runtime、Shizuku 或设备。

## 真机 smoke

`agent.next.app` 最小 shell 已在 Redmi Pad Pro / Android 16 上跑通：connect → create display → 启动京东 → observe → frame → tap → setText → search → task move 往返 → release。搜索页字段回读 `短袖T恤 男款 XL`，结果页 55 节点，主屏 agent 输入被拒绝。详见 [device-adapter-smoke-2026-10-19.md](../docs/device-adapter-smoke-2026-10-19.md)。

窗口切换时 `AndroidTargetObserver.read` 会把瞬时 `IllegalStateException`（双焦点、display mismatch、遍历中 root 变化）映射为 `WINDOW_CHANGED`，不再 invalidate target；客户端重试 observe 即可。最新深 smoke 在 300/200/600/300ms 等待下通过，日志出现 `TARGET_NOT_READY`/`WINDOW_CHANGED → READY`，无 `MALFORMED`。

为支持新 app package：

- `ShizukuBackend(Context)` 用当前 app package 作为 Shizuku user-service component；`UserServiceProtocol` 不再硬编码 `agent.phone`。
- user-service version/tag/process 为 `15` / `agent.next.connection.v15` / `agent_next_shell`。协议 5 与旧服务不兼容时拒绝握手，必须重启 user service 后重连。
- `ShellService(Context)` 用 Context 的 package 解析 owner app UID；无参构造回退到 `agent.next.app`。

这些是对应日期和设备的历史记录。新版发布还需两台设备的输入、preview、接管、服务恢复及真实并发测试，见 [纯 GUI Beta 门槛](../docs/pure-gui-beta.md)。
