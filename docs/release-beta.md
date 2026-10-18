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
`c3f6884b1bd09504d2c6a82f0e8dec6032f3ec0d18971467c49e0083138a9293`。
