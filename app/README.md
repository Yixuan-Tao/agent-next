# app（最小 shell，不是产品 UI）

用于在真机上验证 `capability` → `device-api` 的适配链路。当前只有一个 `ShellActivity`：自动连接 Shizuku、创建 720×1280 private display、启动京东、观察、抓帧、点搜索栏、`setText`、提交搜索、主屏 task move 往返、释放，并把状态写入日志/屏幕。

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
- 完成后看 logcat：

```sh
adb logcat -s AgentNextSmoke:I
```

- 结果包含 `SMOKE PASS` 或 `SMOKE FAIL`。
- 也可点页面底部“Run smoke again”。
- 抓到的首帧写到 app 外部私有目录 `files/smoke-frame.png`，可用 `adb pull` 取出。

## 限制

- 京东首页搜索栏不在 accessibility 树里，当前用固定坐标 `(360,180)` 点击；这是 smoke 探针的妥协，不是新 brain 的 UI 策略。
- 窗口切换后需要等待 1.5–2s 再 observe；立即 observe 可能先得到 `WINDOW_CHANGED`，再触发 `ERROR/MALFORMED`。这是 capability 观察状态待修的边界。
- 主屏上的 agent 输入会被 adapter 拒绝；shell 只验证拒绝，不执行主屏动作。
- 没有自然语言入口、确认卡、接管 UI、前台服务或快慢 loop。
- 目标包写死为 `com.jingdong.app.mall`，仅用于 smoke。
- `Observation.activityName` 为空；见 capability adapter 说明。

真机结果见 [device-adapter-smoke-2026-10-19.md](../docs/device-adapter-smoke-2026-10-19.md)。
