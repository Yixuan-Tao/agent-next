# 设备首轮回归记录（2026-10-19）

状态：**首轮能力回归通过**。本轮验证的是 `testkit/` 的 shell 诊断入口和平台 API，**不是**新 capability AIDL/`ShellService` 的端到端调用；也不包含新 `app`/`brain`。

## 环境

| 项 | 值 |
|---|---|
| 设备 | Redmi Pad Pro `2405CRPFDC`（`dizi`） |
| 系统 | Android 16 / API 36，security patch 2026-08-01 |
| 屏幕 | 1600 x 2560，320 dpi，测试时主屏为横屏 |
| Shizuku | 13.6.0.r1086，`shizuku_server` 以 shell UID 运行 |
| 目标 App | 京东 `com.jingdong.app.mall` 16.1.0 |
| 合成前台 App | `agent.foreground.test` 0.1 |
| 主机 | macOS 27.0.1 arm64，Temurin JDK 21.0.12.1 |
| Android SDK | API 36，build-tools 36.0.0 |

## 结果汇总

| # | 检查 | 结果 |
|---|---|---|
| 1 | 私有 display 生命周期 + 主屏焦点 | **PASS** |
| 2 | 真实目标观察（京东） | **PASS** |
| 3 | 单帧截图 | **PASS** |
| 4 | 定向文本替换（中文） | **PASS** |
| 5 | 定向触摸 + App 页面跳转 | **PASS** |
| 6 | 主屏 task move（往返） | **PASS** |
| 7 | 主屏前台观察 | **PASS** |
| 8 | SharedMemory/Parcel 帧内存探针 | **PASS** |
| 9 | mirror API 签名探测 | **PASS**（仅 API 探测） |

`testkit/qualification/build.sh test` 另跑过 6 个纯 JVM suite，共 1434 个断言，全部通过：FrameRequest 32、InputRequest 77、ObservationBudget 1004、ProbeRequest 70、SessionPolicy 58、TouchRequest 193。

## 详细结果

### 1. 私有 display + 主屏焦点

`QualificationDisplay 1280 800 240` 创建：

- `displayId=146`（本轮临时编号，不能复用）
- `uniqueId=virtual:com.android.shell,2000,agent-phone,144`
- 名称 `agent-phone`，owner shell UID 2000
- 1280 x 800，density 240
- 关键 flags：`FLAG_OWN_FOCUS`、`FLAG_STEAL_TOP_FOCUS_DISABLED`、`FLAG_OWN_CONTENT_ONLY`、`FLAG_DESTROY_CONTENT_ON_REMOVAL`
- `canHostTasks=false`，但 `am start --display` 与 `moveRootTaskToDisplay` 仍可工作

创建 display 后，主屏 display 0 的焦点仍是 `agent.phone/agent.phone.ui.MainActivity`，未被私有 display 抢走。

### 2. 真实目标观察

京东通过 `am start --display 146 -W -n com.jingdong.app.mall/.MainFrameActivity` 启动到私有 display。

`QualificationProbe background ... inspect` 结果：

- `status=ok`，1 个目标窗口 `windowId=3593`
- 296 个节点，299 次 node lookup，`truncated=false`
- 窗口 bounds 为 656 x 800，在 1280 x 800 display 中左右留黑
- 主屏焦点保持 `agent.phone`

### 3. 单帧截图

`QualificationFrame` 生成：

- 1280 x 800 PNG，74,803 bytes
- focused window 列表 `[3594]`
- 画面为京东搜索页，含本轮输入的文字
- 远端文件按 nonce 创建后拉取并删除

### 4. 定向文本替换（中文）

先点击搜索栏进入 `com.jd.lib.search.view.Activity.SearchActivity`，窗口 `3594`。

- 目标字段：`com.jd.lib.search.feature:id/zt`
- 字段状态：`editable=true`、`focused=true`
- 输入：`短袖T恤 男款 XL`（UTF-8，标准 Base64）
- `QualificationInput` 返回 `status=verified`，`actualText` 与输入完全一致
- 重新 observe 后字段 text 仍为 `短袖T恤 男款 XL`

边界：这是 `ACTION_SET_TEXT`，不是中文 IME 组词；IME 输入仍未覆盖。

### 5. 定向触摸 + 页面跳转

- 搜索栏点击：`tap 600 156`，目标 UID 10274，`accepted=true`，2 个事件；进入 SearchActivity
- 搜索按钮点击：`tap 903 85`；进入 `com.jd.lib.search.view.Activity.ProductListActivity`，窗口 `3595`，478 个节点
- 两次操作后主屏焦点均保持 `agent.phone`

### 6. 主屏 task move（往返）

- task move 前：root task `1523` 在 display 146，top 为 ProductListActivity
- `moveRootTaskToDisplay(1523, 0)` 成功；主屏焦点切到京东（用户接管状态）
- display 0 上同时存在 agent.phone、Launcher、Notes、Shizuku、foreground test 等 8 个 root task
- `moveRootTaskToDisplay(1523, 146)` 成功；root task 回到 display 146，主屏焦点恢复 `agent.phone`

### 7. 主屏前台观察

`QualificationProbe foreground 0 <local-uniqueId> agent.phone foreground-selected=yes inspect`：

- `status=ok`，`displayId=0`，包名 `agent.phone`
- 1 个窗口，53 个节点，56 次 lookup，`truncated=false`

### 8. SharedMemory/Parcel 帧内存探针

`FrameMemoryProbe` 在真实 Android 16 上执行 100 轮：

- `content_equal=100`、`read_only=100`、`write_mapping_denied=100`
- `retained_bytes_after_close=100`
- FD 数量 `before=67 / after=67 / min=67 / max=67`
- 写映射拒绝的 errno 为 1（EPERM）

边界：这是同一进程内的 Parcel FD 复制与 SharedMemory 映射，不覆盖跨进程 Binder 服务、真实截图或新 capability 的帧包实现。

### 9. mirror API 签名

`MirrorProbe` 在设备上确认 API 36 的 `VirtualDisplayConfig$Builder` 提供：

```text
ctor(String,int,int,int)
setDisplayIdToMirror(int)
setFlags(int)
setSurface(Surface)
setWindowManagerMirroringEnabled(boolean)
build()
```

本轮只确认 API 可用，**没有**创建真实 mirror、没有验证 app Surface 视频或触摸接管。

## 清理

- 私有 display 已释放；`dumpsys display` 中不再有 `agent-phone` display
- 主屏焦点恢复 `agent.phone`
- 京东已 force-stop
- 本轮推送到 `/data/local/tmp` 的 qualification/task-move/frame-memory/mirror JAR 已删除
- `/data/local/tmp` 中更早会话遗留的 JAR 未动

## 未覆盖

- 新 capability `ShellService`/AIDL 端到端（当前 capability 仍只是 library，没有 `app` 承载）
- 真实 `PreviewMirror` 到 app Surface + 用户触摸接管
- 38 次采样、Parcel 失败注入、重绑/超时清理通过新服务链路
- 合成前台 A18 1200 秒并发长测（需要 Python driver、scrcpy、键盘校准）
- 中文 IME 组词、真实触摸硬件、账号/风控/支付页面
- 主屏接管 UI 与恢复后的重观察

## 证据

原始 JSON/PNG/命令输出保存在本机忽略目录 `work/device-regression/`，包括 `observe-*.json`、`frame-01.png`、`touch-*.json`、`input-01.json`。这些文件不提交，避免把 App 页面文本或设备状态带进仓库。

复现入口：

```sh
JAVA_HOME=<jdk21> \
  QUAL_ANDROID_JAR=<sdk>/platforms/android-36/android.jar \
  QUAL_D8_JAR=<sdk>/build-tools/36.0.0/lib/d8.jar \
  sh testkit/qualification/build.sh build
```

然后按 `testkit/qualification/README.md` 的 `QualificationDisplay` → `am start --display` → `QualificationProbe/Frame/Input/Touch` 顺序执行；task move 使用 `testkit/probes/task-move`。

## 结论

设备身体的第一层（私有 display、目标观察、单帧、定向输入/触摸、主屏 task move、帧内存）在真实 Android 16 上可工作，且主屏焦点在后台执行期间保持稳定。可以继续抽 `device-api` 并接 brain；但在宣称产品级接管前，仍需补上真实 mirror/touch takeover、新服务端到端和 1200 秒并发。
