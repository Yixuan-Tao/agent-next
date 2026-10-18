# capability adapter 真机 smoke（2026-10-19）

状态：**PASS**（含 input/task move 深 smoke）。范围：最小 `app` shell → `BlockingDeviceApi` → `ShizukuConnectionDriver` → capability `ShellService`。这是 adapter 的真实设备端到端第一轮，不是产品 UI 或完整 loop 验收。

## 环境

| 项 | 值 |
|---|---|
| 设备 | Redmi Pad Pro `2405CRPFDC` |
| 系统 | Android 16 / API 36 |
| App | `agent.next.app` 0.1（debug） |
| Shizuku | 13.6.0.r1086，shell 运行 |
| 目标 App | 京东 `com.jingdong.app.mall` 16.1.0 |
| SDK / JDK | API 36 / Temurin 21 |

## 流程

1. `:app:assembleDebug`，安装 debug APK。
2. 启动 `agent.next.app/.ShellActivity`，自动跑 smoke（也可点 “Run smoke again”）。
3. Shizuku 权限弹窗选择“始终允许”。
4. adapter 连接、创建 private display、启动京东、观察、抓帧、释放。

首次安装被 HyperOS 拒绝（`INSTALL_FAILED_USER_RESTRICTED`）。设备上打开/切换 **USB安装**，并启用 **USB调试（安全设置）**（连续确认警告）后安装成功。

## 实测结果

```text
state: CONNECTED/AVAILABLE
connected: protocol=3 api=36 features=8
display: e2f824d4-9e35-41cd-94aa-bb91796db789 generation=1
target: daea737b-84b2-4098-9d2d-6e70695257e0 package=com.jingdong.app.mall
observe: READY nodes=96 window=3649 activity=''
editable=0 clickable=10
frame: 720x1280 png=502899 sequence=1
SMOKE PASS
target released
display released
```

- `DeviceApi.connect()` 从 `DISCONNECTED` 走到 `CONNECTED`，capability 协议 3、API 36。
- private display 为 720×1280/240dpi，token/generation 由服务签发。
- 京东成功启动到 private display；观察 `READY`，96 个节点，无截断。
- 单帧 720×1280 PNG，502,899 bytes。
- 释放 target/display 后，设备上不再有 `agent-phone` display；主屏焦点仍是 `agent.next.app`。
- 京东进程仍在后台，但没有残留私有 display。

## 为接新 app 做的必要修复

- `UserServiceProtocol` 不再硬编码旧包 `agent.phone`：`ShizukuBackend(Context)` 使用当前 app package 作为 user-service component；version/tag/process 改为 `13` / `agent.next.connection.v13` / `agent_next_shell`。
- `InstalledAppIdentity.resolveUid(packageName)`：`ShellService(Context)` 用 Context 的 package 解析 owner app UID；无参构造回退到 `agent.next.app`。
- `BlockingDeviceApi.connect()`：先等初始 `check` 结束（`canConnect`），再请求 Shizuku 权限；否则首次 `connect()` 会在 check 进行中被忽略，表现为卡住。
- 最小 `app` 增加状态日志，真实设备上能看到 `DISCONNECTED → CONNECTING → CONNECTED`。

## 补充：input / task move 深 smoke（同日）

第一轮 smoke 只做 observe/frame。随后把 shell 扩展到输入与主屏接管：

```text
tap search entry: INJECTED
search: READY nodes=96 window=3699
setText: VERIFIED verified=true
typed: READY nodes=96 window=3699
field text='短袖T恤 男款 XL'
tap search: INJECTED
results: READY nodes=55 window=3700
moveToMain display=0
main-screen input rejected: REJECTED
moveToPrivate display=153
restored: READY nodes=55 window=3704
SMOKE PASS
```

- 京东首页搜索栏在 accessibility 树里没有文本/可点击节点，shell 用固定坐标 `(360,180)` 点击；这是 smoke 探针的妥协。
- 搜索页 editable 字段成功 `setText`，`VERIFIED`，回读 `短袖T恤 男款 XL`。
- 点搜索后结果页 `READY`，55 个节点。
- `moveToMain` 后 `displayId=0`，adapter 对 agent 输入返回 `REJECTED`（主屏只能由用户操作）。
- `moveToPrivate` 后结果页仍 `READY`，55 个节点。
- 释放后无 `agent-phone` display，主屏焦点回到 shell。

窗口切换观察已修：`AndroidTargetObserver.read` 把瞬时 `IllegalStateException`（双焦点、display mismatch、遍历中 root 变化）映射为 `WINDOW_CHANGED`，不再让 target 进入 `ERROR/MALFORMED`；客户端重试 observe 即可。最新深 smoke 用 300/200/600/300ms 等待 + `observeReady` 重试通过，日志出现 `TARGET_NOT_READY` / `WINDOW_CHANGED` → `READY`，没有 `MALFORMED`。

## 未覆盖

- preview mirror + 用户触摸接管（需要 app Surface 桥接）。
- 产品 UI、自然语言入口、前台服务、快慢 loop。
- 京东账号/风控/支付页面。
- 更长并发、多 App、中文 IME 组词。

## 证据

本机忽略目录 `work/device-regression/app-shell/`：

- `smoke-pass.log` / `smoke-pass.png`：首轮 observe/frame smoke
- `deep-smoke-pass.log` / `deep-smoke-pass.png`：input/task move deep smoke
- `deep-smoke-fixed.log` / `deep-smoke-fixed.png`：观察修复 + user-service v13 后的最终深 smoke
- 安装/权限过程截图与 UI dump

这些文件不提交，避免把 App 页面内容和设备状态带进仓库。
