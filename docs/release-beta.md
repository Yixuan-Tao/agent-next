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
