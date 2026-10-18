# 模型 API 移植（2026-10-19，文本 + 单帧 PNG）

从旧仓库 `agent-phone` 的 `VisionModel.kt` / `TaskPlanner.kt` 复制 **chat-completions 请求形状**，放到纯 JVM `brain`：

- 旧配置：`private/model-config.json`（`base_url` / `model` / `api_key`）。
- 新配置：已复制到本仓库被忽略的 `private/model-config.json`，**不提交、不打印密钥**。
- 端点：`{base_url}/chat/completions`。
- 请求：`model`、`thinking`、`reasoning_effort=low`、`response_format=json_object`、`max_tokens`；文本用单条 user message，视觉用 text + `image_url`（PNG data URL，`detail=original`）。
- 响应：要求 `choices[0].finish_reason == stop`，读取 `choices[0].message.content`。
- 网络：`HttpURLConnection`，30s 连接/读取超时，响应上限 1 MiB，非 200 只报状态码。

## 代码

| 类型 | 作用 |
|---|---|
| `model.ModelConfig` | 加载旧格式配置；`toString` 隐藏 key |
| `model.ChatCompletionTransport` | HTTP seam，测试用 fake |
| `model.HttpChatCompletionTransport` | 真实 HTTP 调用 |
| `model.ChatCompletionClient` | 请求体、finish_reason、content 校验 |
| `model.DeepSeekSkillModel` | 把模型 JSON 解析成 `SkillModelStep`（语义 selector，无坐标） |
| `model.Json` | 无依赖的受限 JSON parser/string writer |

## 使用

```java
ModelConfig config = ModelConfig.load(Path.of("private/model-config.json")).orElseThrow();
SkillModel model = new DeepSeekSkillModel(new ChatCompletionClient(config));
PromptSkillPolicy policy = new PromptSkillPolicy(skill, Map.of("query", "短袖T恤"), model);
```

`PromptSkillPolicy` 可通过 `SkillFrameSource` 附带当前 PNG；仍只接受 `LOW`/`REVERSIBLE_SIDE_EFFECT` 模型步骤，并经过 FastLoop 的 RiskGate。

## 当前限制

- 已移植文本和**单帧 PNG**输入；旧 `VisionModel` 的 `verify`、`extract` 尚未移植，也没有多帧/视频。
- 没有 app 内模型配置读取/网络调用接线；app 还没有 `INTERNET` 权限。
- host 测试使用 fake transport，**没有发起真实 API 调用**。
- `DeepSeekSkillModel` 拒绝坐标字段/Point selector，但模型仍可能把高风险按钮声称为 `LOW`；trust/validation/独立风险分类仍需要。
- 观察节点会进入 prompt；还没有敏感字段脱敏/裁剪策略。

## 测试

`test-brain.sh` 的 `ModelApiTest` 覆盖：配置加载与 key 脱敏、文本/PNG 请求形状、finish_reason、语义 SetText 解析、坐标字段拒绝。

真实密钥只在忽略的 `private/model-config.json` 中；不要把它写入日志、提交或示例。
