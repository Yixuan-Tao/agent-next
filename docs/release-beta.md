# Internal Beta release APK

状态：**已生成并签名**（未在第二台手机安装验证）

## 构建

```sh
export JAVA_HOME=/path/to/jdk21
./gradlew --offline :app:assembleRelease
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
```

## 签名

- PKCS12 keystore：`private/agent-next-release.jks`（gitignored）
- 配置：`private/release-signing.properties`（gitignored，不提交密码）
- `app/build.gradle` 只在 private 配置存在时启用 release signing；否则 release 不签名。
- `apksigner verify` 结果：

```text
Signer #1 certificate DN: CN=AgentNext Beta, OU=Internal, O=AgentNext, ...
SHA-256: b5a69b7181ee3daaa6c8e996ef2c14952d0e3d0ac652845071a42d21421b6754
```

## APK 元数据

```text
package: agent.next.app
versionCode: 1
versionName: 0.1
minSdk: 33
targetSdk: 36
launchable-activity: agent.next.app.MainActivity
PreviewActivity exported=false   # release
AgentTaskService exported=false
```

## 安装（内部侧载）

debug 与 release 签名不同，直接 `install -r` 会失败。验证 release 时需先卸载 debug：

```sh
adb uninstall agent.next.app
adb install app/build/outputs/apk/release/app-release.apk
```

注意：卸载会清除 app 数据，包括加密 model config 和 AndroidKeyStore key；需要重新导入
`model-config.json` 让 app 重新迁移加密。

## 未完成

- 尚未在 Android 13 手机安装/验收。
- 尚未生成正式 release notes、隐私政策、校验和/签名指纹分发页。
- debug 与 release 使用不同签名；内部 Beta 统一使用 release keystore。

## 2026-10-19 安装尝试（阻塞）

为验证 release，先卸载 debug，再 `adb install` release；MIUI 返回：

```text
INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
```

已确认：

- 开发者选项中的“USB 安装”开关为开启；
- `settings put global verifier_verify_adb_installs 0` 等无效；
- `pm install`/Package Installer intent 同样被 MIUI 安全中心拦截；
- 随后重启平板，adb 设备暂时从本机消失（USB 调试授权/连接待恢复）。

当前状态：**app 已被卸载，平板 adb 暂不可用**。需要人工：

1. 重新连接/授权 USB 调试；
2. 在 MIUI 安装确认弹窗中允许安装；
3. 安装 `dist/agent-next-beta-0.1.apk`；
4. 打开 Settings → 导入 `model-config.json`（新增的 document picker）。

在此之前，Android 13/release 安装验收无法继续。

## 自动校验

```sh
JAVA_HOME=/path/to/jdk21 ANDROID_SDK_ROOT=/path/to/android-sdk \
  sh tools/verify-release.sh
```

检查：

- apksigner 证书 `CN=AgentNext Beta`；
- package `agent.next.app`；
- minSdk 33 / targetSdk 36；
- launcher MainActivity；
- release `PreviewActivity` exported=false；
- 与 `dist/agent-next-beta-0.1.apk.sha256` 一致。

本次输出：`RELEASE PASS`，SHA-256
`e94863daa2c3fcd3373ed5026b16d768d629d6d032c9e7c2085f981032024e94`。

## 2026-10-19 第二次安装尝试

重试 release 安装仍失败，随后 debug 也无法重装：

```text
adb install app-release.apk -> INSTALL_FAILED_USER_RESTRICTED
adb install app-debug.apk   -> INSTALL_FAILED_USER_RESTRICTED
```

已确认：

```text
adb_enabled=1
adb_install_need_confirm=0
verifier_verify_adb_installs=0
install_non_market_apps=1
```

开发者选项“USB安装”开关为开启；“USB安装管理”列表为空。
通过 Package Installer / `pm install` / MIUI `InstallStart` 均被安全中心拦截。

当前：**app 未安装**；APK 已放在 `/sdcard/Download/agent-next-debug.apk`。
需要人工在 MIUI 安装确认中允许安装（或关闭“USB安装”再打开并重新授权 USB 调试），
之后才能继续真机验收。

## 2026-10-19 Release 安装成功（文件管理器路径）

`adb install` 仍被 MIUI 拦截，但通过文件管理器打开 release APK 成功：

1. 文件管理器 → 最近 → `agent-next-release.apk`；
2. MIUI ICP 提示 → “继续安装”；
3. 勾选“已了解此应用未经安全检测”；
4. “继续安装”。

证明：

```text
package: agent.next.app
versionName=0.1
flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
```

- `DEBUGGABLE` 已消失，确认安装的是 release；
- MainActivity / SettingsActivity 可打开；
- release Settings 显示 `model config: 未导入`；
- release 的 model config 需要通过 Settings document picker 导入（本次未完成文件选择）。

结论：**release APK 安装/启动/设置页 PASS**；release 的 config 导入和任务执行仍待完成。

## Release 端到端 PASS（Android 16 平板）

```text
package flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]  # 非 debuggable
Settings: model config 加密文件可用；导入并加密成功
Shizuku: 始终允许
JD 首页任务:
state: RUNNING · 执行中
result: DONE · all subgoals verified actions=2 attempts=4
```

- release APK 通过文件管理器安装；
- 首次 planning 触发 Shizuku 授权，允许后正常；
- Settings document picker 导入 config 成功；
- JD 首页任务 DONE，无支付/下单；
- 前台服务/通知正常。

结论：**release 安装、启动、config 导入、Shizuku、planner/execute/DONE 全链路通过**（当前仅 Android 16 平板）。
Android 13 手机与 Chain B 仍未验收。

## 2026-10-19 后续 release（cart prelude）

重新构建并安装 release，包含 JD cart scoped prelude；SHA-256
`e94863daa2c3fcd3373ed5026b16d768d629d6d032c9e7c2085f981032024e94`。
cart 任务端到端仍未跑通，不以已编译代替已验证。

## 2026-10-19 JD cart 导航 PASS

```text
result: DONE · all subgoals verified actions=1 attempts=3
```

release 上从 JD 首页进入购物车，视觉 verifier 识别商品/数量/价格，未结算。
这是 Chain B 的购物车段通过；商品详情/加购仍受 JD 风控/无障碍限制。
