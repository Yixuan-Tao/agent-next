# 前台自动操作隔离测试

用于当前 Android 16 真机实验。主屏安装一个专用原生测试 App，电脑代替用户点击软键盘、按钮和列表。独立显示继续使用原有 AndroidInputProbe。此工具不是手机独立运行的 AI Agent。

## 文件

`src/` 为原生 App，`driver.py` 为电脑驱动，`calibrate.py` 为测量前的英文模式校准，`fixture_server.py` 与 `fixture/` 提供独立浏览器合成页面。`foreground-test.apk` 可通过 ADB 安装。无第三方 App 运行依赖。

## 构建与校验

需要 Google 官方 API 36 的 android.jar、macOS build-tools 36.0.0、Java 21。将工具和构建中间文件放在忽略的 work 目录，并设置四个环境变量：

```sh
export TEST_SDK_TOOLS=/absolute/work/tools/android-16
export TEST_ANDROID_JAR=/absolute/work/tools/android-36/android.jar
export TEST_JAVA_HOME=/absolute/work/tools/temurin21/Contents/Home
export TEST_BUILD=/absolute/work/foreground-build
sh build.sh
python3 -m unittest test_driver -v
```

build.sh 使用 javac/AAPT2/D8/zipalign/apksigner，生成仅供测试的本地签名 APK，并验证签名。密钥只写入 TEST_BUILD；不是产品发布密钥。

## 运行协议

1. 连接已授权 USB 调试的设备；系统可能要求首次开启“USB 安装”。安装 APK，在 display 0 明确启动 `agent.foreground.test/.TestActivity`，通过 `--es session` 提供一个新的 32 位小写十六进制会话标识。
2. 主屏必须是专用测试 App，不能是私人笔记或其他 App。读取 `content://agent.foreground.test.state/state` 的 payload（Base64 JSON）核对会话、display=0、resumed 和心跳。Provider 只允许 UID 2000 查询，不提供写接口。
3. 校准实际软键盘。当前设备为微信输入法悬浮键盘，必须明确切到英文模式，并通过实际点击 a、删除后精确读回空串验证。新建 Activity 可能恢复中文模式；URI 类型本身不能保证英文模式。可用 calibrate.py（同样提供 adb/serial/session/keys/run-dir）进行这个测量前步骤，空输入框是其前提。若主屏尚无焦点，只在验证没有外来可见窗口后允许一次准备点击；运行中禁止这种恢复。准备成功不能抵消创建显示时造成的干扰。校准不得算入正式时长。
4. 准备 `keyboard.json`，包含 `rotation`、`imeBottom` 和 `keys` 字典；keys 包括 a/s/d/f/backspace/enter/language 的物理像素坐标。坐标必须来自确认属于测试 App 的画面，不能跨设备复用。本目录的 keyboard.redmi-pad-pro.json 是本次平板横屏与悬浮键盘布局的已校准示例。当前微信输入法在明确收起再打开后会恢复中文，因此设备配置还需 `resetLanguageOnReopen: true`，让显隐步骤明确切回英文。连续打字区间不会进行这种切换。
5. 先运行基线。以实际 session、adb 路径、设备 ID 替换占位值：

```sh
python3 driver.py --adb /absolute/adb --serial DEVICE_ID \
  --session SESSION_HEX --keys /absolute/keyboard.json \
  --run-dir /absolute/work/baseline --phase baseline --duration 60
```

6. 基线通过后，以关闭自动剪贴板同步、不接管音频的已验证 scrcpy 配置创建独立显示，动态读取其逻辑编号与 uniqueId。将原输入探针 JAR 推送到 `/data/local/tmp/codex-android-input-probe.jar`，反向映射端口 38768。前台 App 不建立 UiAutomation 连接。
7. 启动合成网页服务器及并行短测；独立画面的编号必须大于 0：

```sh
python3 fixture_server.py --receipts /absolute/work/receipts.jsonl
python3 driver.py --adb /absolute/adb --serial DEVICE_ID \
  --session SESSION_HEX --keys /absolute/keyboard.json \
  --expected-text '准确的当前测试文字' \
  --run-dir /absolute/work/parallel --phase parallel --duration 60 \
  --display SECONDARY_ID --unique-id EXACT_UNIQUE_ID \
  --receipts /absolute/work/receipts.jsonl
```

多行预期文字须由程序参数数组传入，避免 shell 转义改变内容。完成短测后才能将时长设为 1200。不要重用日志目录；脚本会拒绝覆盖。需要停止时，在该 run-dir 创建 stop.txt，或停止驱动进程。

## 判断与局限

每个键都检查实际文字，同时验证文字变更历史，避免临时混入后又恢复被漏报；按钮用累计预期计数。事件缺口、进程序号或会话重置、界面心跳停滞、测试 App 离开主屏、主窗口丢失焦点、连续打字期间 IME 隐藏均停止。失焦表示无法安全继续注入，需分析，不能单凭它认定用户体验必然中断。滚动轨迹限制为最多10Hz，文字、焦点与生命周期事件不做降采样；200ms 心跳仍报告实际滚动位置。

每 20 秒会明确点击测试 App 的“收起键盘”按钮，再点击计数按钮、滚动列表、点击编辑框重新打开键盘。连续打字区间内不重新点击编辑框。收起步骤是测试操作，不是异常后的自动恢复。旧的系统返回键与悬浮键盘工具栏方法在校准中失败，未用于最终协议。

独立页面必须提供本轮 JS ready/scroll 回执，文字使用原探针精确读回。所有 worker 退出后再做一次只读前台核对，才记录 completed。完整 1200 秒需要实际完成事件；提前停止不能当作通过。

日志中的 confirmation_seconds 包含 USB 命令、ContentProvider 和采样耗时，不能当作纯触摸延迟。当前自动化覆盖英文键盘、删除、换行、指定按钮/列表及独立网页；中文组词、真实触摸硬件、主观音画质量、其他 App 和手机脱离电脑运行仍需分别验证。无系统剪贴板读写，无默认输入法变更。

## 官方来源

- [Android UI Automator](https://developer.android.com/training/testing/other-components/ui-automator)：系统级 UI 测试背景。
- [ContentProvider](https://developer.android.com/reference/android/content/ContentProvider)、[Binder.getCallingUid](https://developer.android.com/reference/android/os/Binder#getCallingUid())：只读状态与调用者边界。
- [WindowInsets.Type.ime](https://developer.android.com/reference/android/view/WindowInsets.Type#ime())、[键盘可见性](https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility)：状态观察和明确的收起操作。
- [InputType](https://developer.android.com/reference/android/text/InputType#TYPE_TEXT_VARIATION_URI)：普通 URI 类型，不使用密码字段。
- [AAPT2](https://developer.android.com/tools/aapt2)、[apksigner](https://developer.android.com/tools/apksigner)：APK 构建与校验。
