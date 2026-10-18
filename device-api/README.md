# device-api（纯 JVM 设备边界）

brain 与 capability 之间的接口层。**不依赖 Android**，Java 8 兼容，方便 capability 的 Android library 实现；brain 使用 Java 17，但只通过这里的类型调用设备。

## 设计边界

```text
app → brain → device-api ← capability
```

- `device-api`：端口、句柄、观察/帧/输入 DTO、错误码。
- `capability`：实现端口；负责 AIDL/Shizuku/display/target/shell 细节。
- `brain`：只依赖端口，不 import `agent.phone.*`，不接触 `ShellService`、AIDL、Parcel 或 Android `Surface`。
- 句柄（display/target/observation）由能力端签发，携带 token + generation；数字 display ID 本身不是授权。
- 一次操作必须带当前观察；旧观察/旧句柄由实现端拒绝。

## 端口

| 端口 | 作用 |
|---|---|
| `DeviceApi` | capabilities + 四组控制器 + close |
| `DisplayController` | 创建/检查/释放私有 display |
| `TargetController` | 授权并启动目标、查询状态、释放 |
| `ObservationController` | 观察 UI 树、抓取单帧 |
| `InputController` | 定向 tap/setText/swipe/back |
| `TaskController` | 主屏接管（moveToMain）与回到私有 display |

`DisplaySpec.standard()` 对应 capability 当前支持的 720×1280 / 240dpi；自定义尺寸由实现端决定是否接受。

## 错误

`DeviceException.Code`：

- `UNAVAILABLE`：未连接/已关闭；
- `TIMEOUT`：等待服务/用户超时；
- `REJECTED`：请求违反策略或参数；
- `STALE`：句柄/观察已失效；
- `BUSY`：已有其他 target/display/observer；
- `PROTOCOL`：包/版本/响应不合法；
- `INTERNAL`：实现端未分类失败。

## 明确不在这个端口内

- 模型、任务图、技能、风险门（`brain` 策略层）；
- Surface/视频 mirror（app 层与 capability 的 `PreviewMirror` 桥接，brain 只发接管请求）；
- 用户确认卡/预览 UI；
- IME/剪贴板/密码填充（决策 D3/D7）。

`PREVIEW_MIRROR` 和 `USER_TOUCH` 仍作为 `DeviceFeature` 暴露，供 app 层查询能力；端口本身暂不接收 Android `Surface`。

## 契约测试

```sh
JAVA_HOME=/path/to/jdk21 sh tools/test-device-api.sh
# 或
./gradlew :device-api:hostTest
```

`DeviceApiContract` 定义端口语义；`FakeDeviceApi` 是参考实现/测试替身。capability adapter 接入后应复用同一套契约。
