# capability（Android 设备身体）

Android library：AIDL、Shizuku 连接、私有 display、target 会话、观察/帧、定向输入、preview mirror、主屏 task move、安全/协议策略。

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
- `TargetController.release` 只清理客户端 target 绑定；远端 target 随 private display 释放清理。若同一 display 上还要重新 bind，先释放 display 再重建。
- `Observation.activityName` 目前为空：capability 的 observation 还没有当前 Activity 字段；`TargetInfo.activityName` 是启动 Activity。
- `InputReceipt.VERIFIED` 表示服务端文本回执/回读路径被接受；adapter 不额外再读字段。
- `TargetInfo.foreground` 目前恒为 `true`；`Frame.capturedAt` 是 adapter 接收时间，序列/尺寸来自服务端证据。
- preview mirror 和用户触摸注入（`userTap`/`userSwipe`）仍由 app 层直接用 `ShizukuConnection`，`DeviceApi` 端口暂不接收 Android `Surface`。

## host 契约

```sh
JAVA_HOME=/path/to/jdk21 sh tools/test-capability-adapter.sh
```

脚本用 `FakeConnectionDriver` 在 host 上跑 `DeviceApiContract`，不依赖 Android runtime、Shizuku 或设备。

## 真机 smoke

`agent.next.app` 最小 shell 已在 Redmi Pad Pro / Android 16 上跑通：connect → create display → 启动京东 → observe → frame → tap → setText → search → task move 往返 → release。搜索页字段回读 `短袖T恤 男款 XL`，结果页 55 节点，主屏 agent 输入被拒绝。详见 [device-adapter-smoke-2026-10-19.md](../docs/device-adapter-smoke-2026-10-19.md)。

窗口切换后立即 observe 可能先 `WINDOW_CHANGED` 再 `ERROR/MALFORMED`；shell 用 1.5–2s 等待规避。这是接 brain loop 前需要修的观察状态问题。

为支持新 app package：

- `ShizukuBackend(Context)` 用当前 app package 作为 Shizuku user-service component；`UserServiceProtocol` 不再硬编码 `agent.phone`。
- user-service version/tag/process 为 `12` / `agent.next.connection.v12` / `agent_next_shell`。
- `ShellService(Context)` 用 Context 的 package 解析 owner app UID；无参构造回退到 `agent.next.app`。

真机 input/task move 尚未通过 adapter 验证。
