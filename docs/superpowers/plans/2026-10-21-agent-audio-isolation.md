# Agent 后台音频隔离实施计划

**Goal:** Agent 使用目标 App 时默认静音，只有用户明确接管时恢复；电脑只读监看不恢复声音。

**Architecture:** Shizuku shell 注册仅匹配目标 UID 的 loopback AudioPolicy，将其音频送入丢弃缓冲；不修改全局音量或持久 AppOps。策略 Binder 随服务进程结束而释放。音频所有权由纯 Java 状态机管理，接管和移屏更新同一状态；平台不支持时阻止任务启动。

**Tech Stack:** Java 17 / Android 16 AOSP AudioPolicy、AudioMix、AudioRecord；通过现有 Shizuku/AIDL 链路，协议升级避免旧服务静默绕过。

## 影响文件

- 新增 `capability/src/main/java/agent/phone/executor/TargetAudioIsolation.java`：UID 与接管所有权、失败及资源释放。
- 新增 `capability/src/main/java/agent/phone/executor/AndroidTargetAudio.java`：系统音频路由资源，音频不保存或上传。
- 新增 `capability/src/test/java/agent/phone/executor/TargetAudioIsolationTest.java`：静音、接管、回交、故障回归。
- 修改 `TargetLifecycle` / `ShellService`：启动前建立音频隔离、移屏及退出清理。
- 修改 AIDL、connection 和 `BlockingDeviceApi`：区分交互接管与只读镜像，保留现有超时补偿。
- 修改 service/adapter 测试及 README、隐私和演练说明。

## 执行

- [x] 先写状态机回归并运行，确认缺失实现导致失败。覆盖 `bind → muted`、`preview(true) → audible`、`preview(false) → muted`、`main(true) → audible`、`close → released`，新静音资源创建失败不能提交状态，释放不确定时不能丢失所有权。
- [x] 实现纯状态机及 Android 后端。仅匹配已授权目标 UID，使用 LOOP_BACK 而非 RENDER；创建资源失败必须补偿注销。
- [x] 接线真实目标生命周期及预览模式参数；升级协议。只读监看传 false，交互接管传 true；停止镜像先恢复静音，返回 Agent 前完成静音。
- [x] 运行 `QUAL_JAVA_HOME="$JAVA_HOME" sh tools/test-service.sh`、随后 `sh tools/test-shizuku-adapter.sh`，以及 `sh tools/test-capability-adapter.sh`，检查全部通过。
- [x] Android 构建、同签名安装；验证真实系统接受目标 UID 的隔离策略、接管释放、回交重建及停止清理。对未验证的实际扬声器/音频焦点行为单独标注。
- [x] 更新文档：按 UID 隔离不等于按 display 隔离，不覆盖同 UID 多窗口、完整音频焦点隔离或设备兼容性承诺；旧 APK 的三次成功不算新版通过记录。

## 结果与证据

2026-10-21 在 Redmi Pad Pro / Android 16 上完成：策略注册成功；静音期间目标 UID 播放路由从扬声器（deviceIds `[2]`）切换到当次策略专属 “Remote Submix Out”（`[226]/[243]/[260]`，对应注册 `ap:13/14/15`），注销后立即回到 `[2]`；旁观 UID 全程留在 `[2]`；全局音量/静音不变；read-only 监看不改变静音；release 后注销。修复 AudioFlinger trusted-uid 归属校验（`createFromTrustedUidNoPackage` 拒绝应用上下文包名）后 sink 建立成功；修复前失败会阻止目标启动。详细时间线、dump 摘录与未验证边界（同 UID 窗口、真实 App 播放内容、硬件 offload、耳机/蓝牙、音频焦点、第二设备）见[设备验证记录](../../device-audio-isolation-2026-10-21.md)。

## 复核后追加修复（2026-10-21）

独立只读复核后追加，并重新通过 host 全量回归与最终包设备复验：

- app 层交还门控：`handoffToUser` 与 `PreviewActivity.onDestroy` 先确认 stopPreview/重新静音成功再恢复 Agent；未确认保持暂停。
- 镜像不确定状态：`BlockingDeviceApi` start 失败时补一次 stop；`releaseDisplay`/`destroy`/shutdown 逐步清理，音频注销不再被前面清理失败跳过。
- 移动失败补偿：回私有屏移动失败恢复用户声音或标记 invalid；主屏恢复失败要求清理；目标被用户切前台拒绝操作时把声音交还用户。
- 资源清理：`AndroidTargetAudio.close` 逐项尝试 stop/release/unregister 并聚合异常；`TargetAudioIsolation.close` 在 lease 失败后仍尝试 backend close，确认成功才复位所有权。
- 归属补丁 best-effort 回退；user-service 版本/标签升级到 `14` / `agent.next.connection.v14` 防止旧进程复用。
- 回归补充：同 UID bind 幂等、主屏失败保留所有权、释放失败重试、绑定后前台冲突交还声音、协议 v14 包结构。

仍未完成：真机完整模型任务 handoff 流程、API 33、真实第三方 App 播放内容、耳机/蓝牙与音频焦点场景。

## 依据与边界

参考 Android 16 `AudioPolicy.createAudioRecordSink`、`AudioMix.ROUTE_FLAG_LOOP_BACK`、`AudioMixingRule.RULE_MATCH_UID` 及 AudioService 的策略注销和 Binder death 清理。音频只在本地短暂缓冲后丢弃。不同 App 不参与匹配，用户原有系统音量和静音状态不被修改；接管只是释放本策略，不强制提高音量。
