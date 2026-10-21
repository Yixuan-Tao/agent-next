# Android 13（Pixel 4 XL）smoke 记录

日期：2026-10-21。设备：Pixel 4 XL / Android 13 / API 33；Shizuku 已安装并运行（`shizuku_server`，shell uid）。
APK：Demo debug build，SHA-256 `c652008c977c27b6fd74f639830b05d733bd5cd8ffa4fd77b1b52104c7d08ae4`（与 2026-10-21 京东录屏同一构建）。

## 复现

```sh
adb -s <device> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device> shell am start -n agent.next.app.debug/agent.next.app.ShellActivity \
  --es mode compat --es package com.android.chrome
adb -s <device> logcat -s AgentNextSmoke:I AgentShellService:I AgentShellAudio:I
```

## 结果

已通过：

- Shizuku 连接：`protocol=5, api=33, supported=true`，features 包含 `PRIVATE_DISPLAY / TARGET_LAUNCH / TARGET_OBSERVATION / FRAME_CAPTURE / DIRECTED_INPUT / TASK_MOVE / USER_TOUCH`；
- 私有 display 创建成功；
- 目标 App（Chrome）`target launch status=READY`；
- 观察成功：`target observe status=READY nodes=5`，返回包名 `com.android.chrome`。

失败：

- 截帧：`Screenshot dimensions are not confirmed`（重试 3 次后失败），上层收到 `capture frame failed: ERROR/MALFORMED`。判断为 API 33 上 `UiAutomation.takeScreenshot()` 返回的位图尺寸与虚拟屏 `1080×1920` 不一致，当前实现直接按固定尺寸校验并拒绝。
- 预览镜像：`PreviewCapabilities{..., usable=false, detail=NoSuchMethodException}`，API 33 上预览镜像方法不可用。
- 首次用 `com.android.settings` 探测时在 bind target 阶段失败（`bind target failed: ERROR/MALFORMED`）；换 Chrome 后可到 observe，因此以截帧失败作为当前阻塞点记录。

## 结论与边界

- Android 13 上 Shizuku、私有屏、目标启动和无障碍观察可用；
- 截帧与预览镜像不可用，而纯 GUI 任务依赖截图做视觉核验，因此 **Android 13 端到端任务未通过**，暂不满足题目验收；
- 这只是一台设备、一个目标 App 的 smoke，不构成 Android 13 兼容性结论；后续需要在 Android 13 上替换截帧通道（按实际位图尺寸或按 display 截图）并重新验证。
