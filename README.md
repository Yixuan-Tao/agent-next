# agent-next

新项目的 **capability 种子仓库**。当前只包含设备能力层和测试工具，不包含新的任务大脑（TaskGraph/Skill/RiskGate）或产品 UI。

来源：从旧仓库 `agent-phone`（提交 `5846095`）按 [新项目保留清单](docs/new-project-retention.md) 复制。旧仓库保留为证据和参考，不在这里通过 submodule/源码依赖引用旧 `AgentLoop`/`TaskPlanner`/`MainActivity`。

## 当前包含

```text
capability/   Android library：AIDL、Shizuku 连接、display/target、
              observation/frame、定向输入、preview mirror、主屏 task move、安全策略
testkit/      合成前台 App、准入探针、设备故障探针、旧 loop 的布景脚本（仅参考）
tools/        capability 的 host 回归脚本
docs/         loop 重设计 + 保留清单 + capability 契约 + 决策记录
```

尚未包含：

- `brain/`：新的 TaskGraph、Skill、Memory、EffectVerifier、RiskGate、快慢 loop；
- `app/`：新的自然语言 UI/人工网关/前台服务；
- 新 loop 的真实设备验收；
- 真正的视频/文字接管 UI（capability 只提供底层能力）。

## 构建

需要 Android SDK 36、JDK 21、Gradle wrapper。`local.properties` 不提交，按本机 SDK 路径创建。

```sh
JAVA_HOME=/path/to/jdk21 \
GRADLE_USER_HOME=/path/to/gradle-cache \
./gradlew --offline :capability:assembleDebug
```

`capability` 是 Android library；不会产出可启动 APK。新项目的 `app` 模块后续接入。

## host 回归

```sh
QUAL_JAVA_HOME=/path/to/jdk21 sh tools/test-service.sh
GRADLE_USER_HOME=/path/to/gradle-cache \
QUAL_JAVA_HOME=/path/to/jdk21 sh tools/test-shizuku-adapter.sh
```

已验证：

- `:capability:assembleDebug` BUILD SUCCESSFUL；
- `test-service.sh` 全部 PASS（服务/display/target/observation/frame/packet）；
- `test-shizuku-adapter.sh` PASS（真实 13.1.5 AIDL 适配、UID/callback/cancel 竞态）。

## 新项目边界

- capability 只提供“设备身体”：私有 display、目标会话、观察/截图、定向输入、预览镜像、主屏 task move、安全校验。
- 新 brain 不得直接依赖 Android `ShellService`/坐标；只能通过 capability API。
- 旧 loop 的固定计划、预设、条件/预算数字不作为新设计基线。
- 测试工具中的旧 model-loop 脚本只用于布景/参考，不代表新 loop 验收。
- 文档计划：[docs/loop-redesign.md](docs/loop-redesign.md)；保留/迁移：[docs/new-project-retention.md](docs/new-project-retention.md)；已定决策：[docs/decisions.md](docs/decisions.md)。
- capability 约束：[docs/device-capability.md](docs/device-capability.md)。

## 关于目录

当前 `agent-next/` 位于旧工作区下，是一个**独立 Git 仓库**，便于在文件沙箱内创建；正式接入远程前可整体移动到同级目录，不影响内部路径。
