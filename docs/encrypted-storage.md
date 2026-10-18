# 本地加密存储（Phase 4 进行中）

状态：**代码已接入并在 Android 16 平板验证迁移与读写**。

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

## 设备验证

```text
files/model-config.enc  185 bytes
files/episodic.enc      805 bytes
```

- 旧 `model-config.json` 已删除；
- 真实 planner 正常，任务 `DONE actions=1 attempts=2`；
- `episodic.enc` 头部为 `AGNX1` magic + IV + 密文，不是明文 JSON：

```text
00000000: 4147 4e58 31b6 a806 a8a6 fb00 31b7 601e  AGNX1.......1.`.
```

实现关键点：Android Keystore 不允许调用方提供 IV
（`Caller-provided IV not permitted`），已改为让 Keystore 生成随机 IV 并从
`Cipher.getIV()` 写入文件；解密仍使用文件中的 IV。

## 待做

- Settings/隐私页的“清除记忆/更换 key”UI；
- 任务结束后的保留/删除策略。

## 边界

- AndroidKeyStore key 不随 app 数据备份迁移；清除 app 数据后需要重新导入 config。
- 加密只保护静态文件；运行中的 `ModelConfig`/frame 仍在进程内存。
- 不存原始截图、完整 UI 树、密码/验证码。
