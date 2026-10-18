# 本地加密存储（Phase 4 进行中）

状态：代码已接入，**设备迁移验证待 Shizuku 连接恢复后补**。

## 目标

- 模型 API key 不长期以明文留在 app files；
- episodic memory 只存结构化事件，不存原始帧/完整 UI 树；
- 删除任务/清空记忆有明确入口。

## 实现

### model config

`EncryptedModelConfigStore`：

- 新格式 `files/model-config.enc`；
- AES-GCM，key 在 AndroidKeyStore（alias `agent-next-local-store-v1`）；
- 首次加载发现旧 `files/model-config.json` 时：
  1. 读取并解析；
  2. 加密写入 `model-config.enc`；
  3. 删除旧明文文件；
- 解密后只在内存构造 `ModelConfig`，不写临时明文；
- 加密文件损坏/密钥不可用时 fail closed，不回退明文。

### episodic memory

`EncryptedEpisodicMemory` implements `EpisodicMemory`：

- 文件 `files/episodic.enc`，同样 AES-GCM；
- 只保存 `TaskEvent(taskId, at, type, summary, data)`；
- 最多 500 条，超出删除最旧；
- `deleteTask(taskId)` / `clear()`；
- 写失败不阻塞任务，当前进程内仍可读。

## 待验证

- 设备上首次 plan 后检查：
  - `files/model-config.enc` 存在；
  - `files/model-config.json` 不存在；
  - 真实 planner 仍可用；
  - `files/episodic.enc` 有加密内容，不能直接读出 JSON。
- 当前测试时 Shizuku user service 反复 `System.exit`，导致 app connect 超时；这是测试环境阻塞，不是加密代码路径的结论。
- 尚未实现 Settings/隐私页的“清除记忆/更换 key”UI。

## 边界

- AndroidKeyStore key 不随 app 数据备份迁移；清除 app 数据后需要重新导入 config。
- 加密只保护静态文件；运行中的 `ModelConfig`/frame 仍在进程内存。
- 不存原始截图、完整 UI 树、密码/验证码。
