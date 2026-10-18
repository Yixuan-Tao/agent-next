# Settings / privacy surface（Android 16 平板）

状态：已实现并真机打开；release 中不导出

## 内容

`SettingsActivity`：

- 显示 model config 是否为加密文件可用（不显示 key）；
- 显示 episodic memory 事件数；
- “清除 episodic memory”按钮；
- 隐私说明：
  - API key 使用 AndroidKeyStore AES-GCM；
  - episodic 只存结构化事件，不存原始截图/完整 UI 树；
  - 停止/失败不会补发或自动支付；
  - 当前无云端账号、分析 SDK、社区 registry。

## 真机

- debug 入口（debug manifest `exported=true`）打开正常；
- 显示 `episodic memory: 163 events`；
- 点击清除后 `episodic.enc` 变为约 35 bytes（空数组密文），界面刷新。

## 限制

- 没有“导入 model config”UI；仍通过 adb/文件导入 plaintext 后自动迁移；
- 没有按任务删除/保留策略；
- 没有独立的隐私政策页面/导出审计；
- release `SettingsActivity` 不导出，需从 MainActivity 进入。
