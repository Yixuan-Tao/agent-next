# app（最小 shell，不是产品 UI）

用于在真机上验证 `capability` → `device-api` → `brain` 的链路。当前只有一个 `ShellActivity`：

- 默认 smoke：连接 Shizuku、创建 720×1280 private display、启动京东、观察、抓帧、点搜索栏、`setText`、提交搜索、主屏 task move 往返、释放。
- fast-loop 模式：用 `FastLoop` + 京东脚本策略完成一次搜索并到达结果页。

## 构建与安装

```sh
JAVA_HOME=/path/to/jdk21 \
GRADLE_USER_HOME=/path/to/gradle-cache \
./gradlew --offline :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n agent.next.app/.ShellActivity
```

debug 签名使用被忽略的 `work/signing/debug.keystore`，不是产品密钥。

## 运行

- 首次启动会弹 Shizuku 授权；选择“始终允许”。
- 默认启动跑 smoke；跑 FastLoop 用：

```sh
adb shell am start -n agent.next.app/.ShellActivity --es mode loop
```

- 完成后看 logcat：

```sh
adb logcat -s AgentNextSmoke:I
```

- smoke 结果包含 `SMOKE PASS`/`SMOKE FAIL`；FastLoop 结果包含 `FAST LOOP PASS`/`FAST LOOP STOP`。
- 也可点页面底部 “Run smoke again” / “Run fast loop (JD search)”。
- 抓到的帧写到 app 外部私有目录 `files/smoke-frame.png`，可用 `adb pull` 取出。

## 限制

- 京东首页搜索栏不在 accessibility 树里，当前用固定坐标 `(360,180)` 点击；这是 smoke 探针的妥协，不是新 brain 的 UI 策略。
- FastLoop 的 `JdSearchPolicy` 是脚本策略：用结果页筛选项文本（全部/新品/今日上新/奥莱/明日达）作为完成证据；真实 brain 应由领域 verifier 判断。
- 结果页骨架屏期间用 `WaitAction` 空等；这是 smoke 专用动作，不是通用技能。
- 窗口切换时 observe 会返回 `TARGET_NOT_READY`/`WINDOW_CHANGED`；shell 会重试，服务端把瞬时窗口变化映射成可重观察状态，不再把 target 置为 `ERROR`。
- 主屏上的 agent 输入会被 adapter 拒绝；shell 只验证拒绝，不执行主屏动作。
- 没有自然语言入口、确认卡、接管 UI、前台服务或慢循环重规划。
- 目标包写死为 `com.jingdong.app.mall`，仅用于 smoke。
- `Observation.activityName` 为空；见 capability adapter 说明。

真机结果见 [device-adapter-smoke-2026-10-19.md](../docs/device-adapter-smoke-2026-10-19.md) 和 [device-fast-loop-smoke-2026-10-19.md](../docs/device-fast-loop-smoke-2026-10-19.md)。
