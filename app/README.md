# app（最小 shell，不是产品 UI）

用于在真机上验证 `capability` → `device-api` → `brain` 的链路。当前只有一个 `ShellActivity`：

- 默认 smoke：连接 Shizuku、创建 720×1280 private display、启动京东、观察、抓帧、点搜索栏、`setText`、提交搜索、主屏 task move 往返、释放。
- fast-loop 模式：用 `FastLoop` + 京东脚本策略完成一次搜索并到达结果页。
- runner 模式：先跑一个受控空动作触发 `NO_EFFECT`，由 `SlowLoop` 替换 current subgoal，再由第二轮 `FastLoop` 完成搜索。
- prompt 模式：读取 app 私有 `files/model-config.json`，用真实 DeepSeek chat-completions + `PromptSkillPolicy` 完成一次京东搜索页操作；模型只输出语义动作，完成后用单帧 PNG 做独立视觉 verify/extract。

## 构建与安装

Beta 基线为 Android 13（API 33）及以上；`app` 以 Java 17 编译，`capability` 保持 Java 8。

```sh
JAVA_HOME=/path/to/jdk21 \
GRADLE_USER_HOME=/path/to/gradle-cache \
./gradlew --offline :app:assembleDebug

adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n agent.next.app/.ShellActivity
```

debug 签名使用被忽略的 `work/signing/debug.keystore`，不是产品密钥。

## 运行

- 兼容性准入（不做输入）：`adb shell am start -n agent.next.app/.ShellActivity --es mode compat --es package com.jingdong.app.mall`，结果看 `COMPAT PASS`；详见 [android-13-compat.md](../docs/android-13-compat.md)。


- 首次启动会弹 Shizuku 授权；选择“始终允许”。
- 默认启动跑 smoke；跑 FastLoop 用：

```sh
adb shell am start -n agent.next.app/.ShellActivity --es mode loop
```

- 跑 LoopRunner 重规划用：

```sh
adb shell am start -n agent.next.app/.ShellActivity --es mode runner
```

- 跑真实模型 prompt skill 用：

```sh
adb push private/model-config.json /data/local/tmp/agent-next-model-config.json
adb shell run-as agent.next.app mkdir -p files
adb shell run-as agent.next.app cp /data/local/tmp/agent-next-model-config.json files/model-config.json
adb shell run-as agent.next.app chmod 600 files/model-config.json
adb shell am start -n agent.next.app/.ShellActivity --es mode prompt
```

`model-config.json` 含 API key，只放 app 私有目录/本机忽略目录；不要提交或打印。manifest 已声明 `INTERNET` 权限，因为模型调用走公网 HTTPS。

- 完成后看 logcat：

```sh
adb logcat -s AgentNextSmoke:I
```

- smoke 结果包含 `SMOKE PASS`/`SMOKE FAIL`；FastLoop 结果包含 `FAST LOOP PASS`/`FAST LOOP STOP`；LoopRunner 结果包含 `LOOP RUNNER PASS`/`LOOP RUNNER STOP`；prompt 模式包含 `PROMPT SKILL PASS` / `PROMPT SKILL VERIFY FAIL` / `PROMPT SKILL EXTRACT FAIL` / `PROMPT SKILL STOP`。
- 也可点页面底部 “Run smoke again” / “Run fast loop (JD search)” / “Run LoopRunner (replan)” / “Run prompt model (JD search)”。
- 抓到的帧写到 app 外部私有目录 `files/smoke-frame.png`，可用 `adb pull` 取出。

## 限制

- 京东首页搜索栏不在 accessibility 树里，当前用固定坐标 `(360,180)` 点击；这是 smoke 探针的妥协，不是新 brain 的 UI 策略。prompt 模式的固定入口也是 app trusted prelude，模型只在搜索页开始动作。
- FastLoop 的 `JdSearchPolicy` 是脚本策略：用结果页筛选项文本作为完成证据；prompt 模式用本地 verifier 判定动作完成，再要求视觉 verify 通过才报 `PROMPT SKILL PASS`，不让模型自评完成。
- prompt 模式真实模型曾连续输出两次 SetText；第二次冗余但幂等。模型仍可能把高风险按钮声明为 `LOW`，需要独立分类/验证。
- 结果页骨架屏期间用 `WaitAction` 空等；这是 smoke 专用动作，不是通用技能。
- LoopRunner 模式第一轮是脚本故意空动作，用来稳定触发 `NO_EFFECT`；不代表真实故障恢复。
- `InsertBefore`/`RemoveCurrent` 目前只有 host 测试，没有真机场景；slow-loop 决策仍是脚本策略。
- 窗口切换时 observe 会返回 `TARGET_NOT_READY`/`WINDOW_CHANGED`；shell 会重试，服务端把瞬时窗口变化映射成可重观察状态，不再把 target 置为 `ERROR`。
- 主屏上的 agent 输入会被 adapter 拒绝；shell 只验证拒绝，不执行主屏动作。
- 没有自然语言入口、确认卡、接管 UI、前台服务；模型只接在 prompt skill 上。
- 目标包写死为 `com.jingdong.app.mall`，仅用于 smoke。
- `Observation.activityName` 为空；见 capability adapter 说明。

真机结果见 [device-adapter-smoke-2026-10-19.md](../docs/device-adapter-smoke-2026-10-19.md)、[device-fast-loop-smoke-2026-10-19.md](../docs/device-fast-loop-smoke-2026-10-19.md)、[device-slow-loop-smoke-2026-10-19.md](../docs/device-slow-loop-smoke-2026-10-19.md) 和 [device-prompt-skill-smoke-2026-10-19.md](../docs/device-prompt-skill-smoke-2026-10-19.md)。
