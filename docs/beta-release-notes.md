# AgentNext Internal Beta 0.1

构建：`app-release.apk`（内部签名）  
minSdk：33 / targetSdk：36  
版本：versionCode 1 / versionName 0.1

## 已包含

- 自然语言目标 → DeepSeek TaskPlanner → 计划审阅/确认 → SessionRunner；
- 多 App target 串行切换、视觉 verifier、StageData；
- Chain A：Quark 天气 → calendar 提醒（真机 PASS）；
- JD 搜索（真机 PASS）；
- 共享 Preview + 触摸暂停/恢复；
- 前台服务 + 通知 Stop；
- 加密 model config/episodic memory；Settings/隐私页；
- 风控/验证码 STOP + 人工 handoff 代码；
- 网络/target/Shizuku 故障 fail-closed；
- 408/429/5xx 模型有界重试。

## 已知限制（发布阻塞）

- **Chain B 未完成**：JD 商品详情无障碍树为空，自动化加购/购物车未通过；JD 风控可能触发。
- Android 13 手机未连接，Phase 0 手机 gate 未完成。
- Human handoff 真机链路未验收。
- 20 分钟后台/Doze、private display 回收未验收。
- 无正式 release notes/隐私政策 URL/分发校验页。
- 无社区 registry、多帧视频、任意 App 100% 保证。
- 内部 Beta 单任务、单用户；不支持支付/下单/发送/删除自动化。

## 安装

debug/release 签名不同；先卸载 debug，再安装 release。卸载会清除本地加密 config，
需要重新导入 `model-config.json` 让 app 迁移。

## 证据

见 README 的 verified 列表与 `docs/` 下各 smoke/fault 文档。
