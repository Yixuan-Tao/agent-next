# AgentNext 内部 Beta 隐私说明（草案）

状态：内部侧载 Beta，不是公开发布版本。

## 数据

- 任务文本、目标 App、计划、结构化事件保存在本机。
- 模型 API key 使用 AndroidKeyStore AES-GCM 加密保存；明文 config 迁移后删除。
- episodic memory 仅保存结构化事件（任务 id、时间、类型、摘要、少量文本键值），
  不保存原始截图、完整 UI 树、密码/验证码。
- 运行中的截图/UI 文本会发送给配置的模型服务用于决策；不会上传到 AgentNext 自有服务器。
- 当前无账号、分析 SDK、广告 SDK、社区 registry。

## 用户控制

- Settings 页可查看加密 config 状态、episodic memory 数量并清除。
- 计划执行前必须人工确认；高风险动作再次确认。
- 遇到验证码/风控时停止或人工接管，不绕过。
- 卸载 app 会删除本地数据；AndroidKeyStore key 不再可恢复。

## 边界

- 模型服务的数据保留/隐私政策由所选服务商决定。
- 原始 PNG 仅用于当前决策请求，不写入 episodic memory。
- Beta 不承诺处理敏感账号/支付/验证码场景。

## 待补

- 正式隐私政策 URL/联系方式；
- 数据导出/按任务删除 UI；
- Android 13 权限说明；
- 发布前安全评审。
