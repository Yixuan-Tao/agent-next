# Android App 准入工具

这是前后台执行器的诊断工具，按[准入协议](../../docs/app-qualification.md)逐步扩展。它尚不包含模型或产品级授权服务，不能将其单元检查当作真实 App 操作通过。

## 会话校验

`SessionPolicy` 是纯 Java 8 校验：

- 仅支持已适配的 Android API 36。
- 后台必须是 shell UID 2000 创建、名为 `agent-phone` 的非主屏显示，编号及 uniqueId 严格匹配。
- 前台必须明确选择主屏，且显示身份匹配；该布尔参数只由可信诊断调用方提供，不是生产授权令牌。
- 包名和 generation 必须匹配；拒绝负数、空身份和 Unicode 纯空白。

原浏览器合成页探针保持原样，仍禁止 display 0。本工具不借用或解除其限制。

## 构建与测试

需要 JDK 21、Android API 36 SDK，以及构建 DEX 时的 Android build-tools 36.0.0。只使用本机已准备的工具，不自动下载或接受 SDK 协议。

```sh
export QUAL_JAVA_HOME="/path/to/jdk-21"
export QUAL_ANDROID_JAR="/path/to/android-sdk/platforms/android-36/android.jar"
export QUAL_D8_JAR="/path/to/android-sdk/build-tools/36.0.0/lib/d8.jar"
sh tools/qualification/build.sh test
sh tools/qualification/build.sh build
```

默认产物在被忽略的 `work/qualification/build/`。`test` 运行纯 JVM 守卫测试；`build` 额外生成设备端 DEX JAR。真实显示、界面和停止行为仍需真机验证。

## 只读界面观察

`QualificationProbe` 只支持 `inspect`，没有点击、输入、截图或启动 App 的能力。调用方必须先取得本次实际显示身份；不要复用旧编号。下例中的主屏选择由测试者主动作出，不能作为产品授权协议使用。

```sh
adb push work/qualification/build/qualification.jar /data/local/tmp/agent-phone-qualification.jar
adb shell 'CLASSPATH=/data/local/tmp/agent-phone-qualification.jar app_process / agent.qualification.QualificationProbe foreground 0 DISPLAY_UNIQUE_ID com.jingdong.app.mall foreground-selected=yes inspect'
```

后台调用使用 `background SECONDARY_DISPLAY_ID DISPLAY_UNIQUE_ID PACKAGE foreground-selected=no inspect`。该显示必须满足 `SessionPolicy` 的归属和名称限制；不会改用主屏。

成功时标准输出为一个 JSON 对象，包含模式、显示身份、单调时间、窗口与有限控件。最多查询 1000 个节点位置，文本最多 512 个 code point，截断显式标记，密码节点文本与描述脱敏。原始输出可能含账号页面内容，仅保存在本地私有调试目录。

工具只读取选定显示内包名一致的子树；前后均需存在同一个已聚焦目标窗口。通知栏覆盖、目标切走或显示改变会拒绝并以非零状态退出，不输出部分结果。Accessibility 树可能缺少自绘内容，前后检查也不是原子快照；这一步尚不能替代动作前校验或产品会话服务。

初次根节点、子节点、最终焦点确认共用最多 1000 次查询，JSON 的 `nodeLookupCount` 报告实际值。最终确认额度不足时整次拒绝，不把缺少校验的截断结果当作成功。

## 独立显示启动器

`QualificationDisplay` 从已验证的 API 36 shell 原型整理而来，使用相同的焦点保护配置、own-content 和移除时销毁内容标志，显示名固定为 `agent-phone`。它只创建显示并持续释放 ImageReader 缓冲，不导出画面，也不启动 App。

```sh
adb shell 'CLASSPATH=/data/local/tmp/agent-phone-qualification.jar app_process / agent.qualification.QualificationDisplay 1280 800 240'
```

保持该进程标准输入打开；收到一行或 EOF 后释放显示与 ImageReader。另一个终端从当前设备状态取得新显示编号和 uniqueId 后才建立后台观察，不能硬编码示例编号。释放会结束该显示上的内容，需先停止操作并记录结果。

运行中的启动器使用的 JAR 不应被覆盖。更新探针时推送到另一个版本文件名，并在 `CLASSPATH` 中指定该文件；等待所有使用者退出后再清理文件。调用方还需设置超时和断连清理，不能把诊断进程当作产品常驻服务。

## 指定显示截图

`QualificationFrame` 使用相同的显示校验和显式 display-ID `UiAutomation` 构造器，在截图和写文件前后复核目标窗口。以下大写值都需替换为本轮实际值：

```sh
adb shell 'CLASSPATH=/data/local/tmp/qualification-VERSION.jar app_process / agent.qualification.QualificationFrame background DISPLAY_ID DISPLAY_UNIQUE_ID com.jingdong.app.mall foreground-selected=no NONCE'
```

`NONCE` 必须是新的 32 位小写十六进制标识。成功 JSON 包含原始显示尺寸、截图时间、聚焦窗口和 `/data/local/tmp/agent-phone-frame-NONCE.png`。文件只允许创建，权限为 `0600`；重用 nonce 会拒绝，并保留原文件。拉取本轮图片后删除该远端文件。工具不输出图片 Base64，也不复用缓存帧。

尺寸与坐标均属于整个逻辑显示。京东在横向副屏中出现了纵向内容留边，不能裁掉留边后仍使用原坐标。前台诊断截图也是整屏图，测试者需确保目标 App 独占显示；当前工具没有其他分屏或系统浮层的自动遮盖能力，不应直接作为产品隐私边界。

## 指定编辑框替换文本

`QualificationInput` 接收当前聚焦窗口编号、资源 ID 和 UTF-8 文本的标准 Base64：

```sh
adb shell 'CLASSPATH=/data/local/tmp/qualification-VERSION.jar app_process / agent.qualification.QualificationInput background DISPLAY_ID DISPLAY_UNIQUE_ID com.jingdong.app.mall foreground-selected=no WINDOW_ID VIEW_ID BASE64_TEXT'
```

窗口与资源 ID 必须来自刚取得的观察；资源 namespace 可以属于 App 的库模块。目标必须唯一、可见、启用、可编辑且不是密码字段。最多 256 个 Unicode code point，允许空串清除，拒绝 NUL、畸形 UTF-8 和非规范 Base64。

每次只派发一次 `ACTION_SET_TEXT`，随后刷新字段、双次精确读回，并复核显示、窗口与字段身份。成功输出 `verified`。派发后的失败会明确报告可能已输入，调用方必须重新观察，不可盲目重放。两秒读回期限不负责取消阻塞的 Binder 调用，外层仍需进程超时。登录、验证码和支付保持人工处理。

## 限定目标 App 的触摸

`QualificationTouch` 接收当前聚焦窗口编号及一次点击或滑动：

```sh
adb shell 'CLASSPATH=/data/local/tmp/qualification-VERSION.jar app_process / agent.qualification.QualificationTouch background DISPLAY_ID DISPLAY_UNIQUE_ID com.jingdong.app.mall foreground-selected=no WINDOW_ID tap X Y'
adb shell 'CLASSPATH=/data/local/tmp/qualification-VERSION.jar app_process / agent.qualification.QualificationTouch background DISPLAY_ID DISPLAY_UNIQUE_ID com.jingdong.app.mall foreground-selected=no WINDOW_ID swipe X1 Y1 X2 Y2 DURATION_MS'
```

坐标使用原逻辑显示像素，必须位于期望窗口及显示边界内；滑动时长为 100–1000ms。系统 PackageManager 解析目标包在 user 0 下的普通 App UID，调用者不能提供 UID。每个正常事件前复核显示身份、聚焦窗口、几何与 UID；仅使用三参数 `InputManagerGlobal.injectInputEvent` 定向到该 UID，没有通用触摸降级。

成功回执 `injected/accepted` 只说明输入注入，不代表商品操作完成。派发后失败明确提示可能已经操作、不得自动重试，并尽力向原显示与原 UID 发送 CANCEL。窗口检查与注入之间仍有同 UID 窗口变化的竞态；调用方必须重新观察业务结果。当前真机已验证定向点击、滚动和派发前的旧窗口/越界拒绝，尚未单独故障注入所有 native/CANCEL 分支。

以上均是可信测试调用方使用的诊断入口，尚未实现产品级用户授权、任务 generation、动作日志恢复或模型循环。
