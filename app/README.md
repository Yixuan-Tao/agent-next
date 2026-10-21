# app：手机任务入口与运行时

Android 应用层，负责自然语言入口、自动执行、进展显示、预览接管、设置、前台服务与本地加密存储。目标是承载 [纯 GUI 内部 Beta](../docs/pure-gui-beta.md)，让用户从提出任务到获得可核验结果都能在手机完成。

## 当前入口

| 组件 | 当前职责 |
|---|---|
| `MainActivity` | 输入任务、选择起始应用、直接开始、暂停/继续、停止和必要信息补充 |
| `AgentRuntime` | 内部目标准备与契约校验、AutomaticTaskFlow、ReactiveSessionRunner、进展与交接回调 |
| `AgentTaskService` | 前台通知、停止与人工接管通知 action |
| `PreviewActivity` | 默认只读查看私有 display；用户明确接管后才暂停 Agent 并开放触摸、主屏接管/返回 |
| `SettingsActivity` | 导入模型配置、显示加密状态、查看与清除 episodic memory |
| `ShellActivity` | Debug-only 设备与 loop 诊断，源码在 `src/debug`；不属于产品任务入口 |

默认入口提交自然语言任务后直接执行，内部目标和约束校验通过即进入实时观察循环。只有缺少必要信息时显示澄清问题，补充后自动继续；界面不展示内部计划。执行中显示简短进展并提供暂停、停止和接管。暂停来源独立记录，关闭预览不会解除用户主动暂停。旧 domain 策略保留为历史代码，默认任务入口不再调用。详见[实时执行说明](../docs/reactive-agent-2026-10-21.md)。

Debug 版另有 `DemoFramePublisher` 只读镜像，电脑运行 `tools/demo-viewer.py` 观看，不会暂停 Agent。手机进入 `PreviewActivity` 也先打开只读画面并让 Agent 继续运行；只有用户点击“接管操作（暂停 Agent）”后才切换为交互镜像、开放触摸并暂停 Agent。只读预览占用设备镜像时会暂时切换电脑端帧发布，关闭后按原设置恢复。人工交接先发通知，用户选择接管后才展示交互预览；不自动把目标移到主屏。交接通知在用户接管期间保持“完成，继续/停止”动作，普通状态更新不会覆盖它。声音跟随用户控制：后台执行与只读监看时目标 App 静音，交互接管或移主屏接管时恢复，交还后重新静音；关闭交互预览或从 handoff 通知继续时，会先等服务确认镜像停止且重新静音成功才恢复 Agent，未确认则保持暂停并提示。隔离建立失败会阻止任务启动。详见[提交演练](../docs/submission-demo.md)、[音频隔离验证记录](../docs/device-audio-isolation-2026-10-21.md)与[交接边界验证记录](../docs/device-handoff-2026-10-21.md)。

## 目标运行边界

- GUI skill 只提出语义动作，不持有 `BlockingDeviceApi` 或通过 prelude 直接输入。
- 所有副作用经过统一执行器，组合技能每一步均可取消、核验和计数。
- Service 持有任务；Activity 重建能获得完整快照，进程重启后由用户选择恢复。
- 接管恢复时重新观察，未确认或结果未知的副作用不能重放。
- 最终 UI 展示结果内容、证据摘要和未完成项；安全边界见 [D1–D12](../docs/decisions.md)。

以上是交付目标；接口和 M0–M6 顺序见 [目标说明](../docs/pure-gui-beta.md)。

## 构建与安装

JDK 21、SDK 36 / Build Tools 36.0.0；`app` / `brain` 使用 Java 17，最低安装 API 33。先设置 `local.properties`、`JAVA_HOME` 和 `GRADLE_USER_HOME`。

Debug 构建引用被忽略的 `work/signing/debug.keystore`。若本地尚无密钥，首次在仓库根目录创建：

```sh
mkdir -p work/signing
"$JAVA_HOME/bin/keytool" -genkeypair -keystore work/signing/debug.keystore -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug"
```

仅用于 debug；release 使用独立私有签名配置，见 [release 说明](../docs/release-beta.md)。

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n agent.next.app/.MainActivity
```

依赖缓存齐全时可使用 `--offline`。运行前启动并授权 Shizuku；通过“设置与隐私 → 导入 model config JSON”导入 `base_url`、`model`、`api_key`。当前模型协议要求 chat-completions、JSON 输出和 PNG 图像输入；Android 配置不允许明文 HTTP，请使用 HTTPS。不要提交或打印真实 key。

导入后 app 私有副本加密存储；系统文件选择器中的原始配置文件不会因导入被删除，应由用户管理。详见 [设置](../docs/settings-privacy.md) 与 [隐私说明](../docs/privacy.md)。

## Debug 诊断

`ShellActivity` 启动后会实际执行诊断；默认 smoke 和 loop 会操作京东，测试前确认设备与数据用途。

```sh
# 兼容性探针：连接、display、目标、观察/截图，不注入输入
adb shell am start -n agent.next.app/.ShellActivity --es mode compat --es package com.jingdong.app.mall
# FastLoop 搜索诊断
adb shell am start -n agent.next.app/.ShellActivity --es mode loop
# 脚本故障与重规划诊断
adb shell am start -n agent.next.app/.ShellActivity --es mode runner
# 音频隔离诊断：bind → 只读镜像 → 交互接管开关 → 移主屏/回私有屏 → 释放
adb shell am start -n agent.next.app/.ShellActivity --es mode audio --es package com.jingdong.app.mall
adb logcat -s AgentNextSmoke:I AgentShellAudio:I
```

`mode=audio` 会在 app 进程内播放一条 880 Hz 对照音轨；诊断期间可用 `adb shell dumpsys audio`、`dumpsys media.audio_policy` 对照目标 UID 的播放设备是否在扬声器与策略 remote-submix 之间切换。它只用于验证，不属于产品入口；音频不会保存或上传。

`mode=prompt` 是历史模型 smoke，单独读取私有目录中的旧 `model-config.json`，与产品加密配置路径不同；不要将它当作正式配置流程。诊断可能保存 `smoke-frame.png`，不等同于产品默认帧保留政策。

历史记录：[产品 UI](../docs/product-ui-plan-smoke-2026-10-19.md)、[预览接管](../docs/preview-touch-smoke-2026-10-19.md)、[故障注入](../docs/fault-injection-2026-10-19.md)。这些记录不替代新版纯 GUI 验收。
