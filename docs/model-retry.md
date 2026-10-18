# 模型调用重试/退避

## 行为

- `HttpChatCompletionTransport` 对非 200 返回 `ModelHttpException(status, Retry-After)`；
- `ChatCompletionClient` 默认最多重试 **2 次**（共 3 次调用）；
- 只重试 408 / 429 / 5xx；
- `Retry-After` 优先，单次等待上限 5s；
- 无 header 时指数退避 500ms/1000ms（上限 4s）；
- 4xx 其他错误、JSON/内容错误不重试；
- 中断/线程 interrupt 直接抛 IOException；
- 不在动作执行后自动重放动作；只重试模型 HTTP 调用。

## 测试

host `ModelApiTest`：

```text
429 + Retry-After: 100ms -> 第二次成功
calls=2, sleeps=[100]
```

## 仍未做

- 每日/每任务模型调用预算；
- 429 持续时的 UI 提示/暂停恢复；
- 图像调用独立预算；
- 真实 429/网络抖动注入。

## 模型调用预算

- `ModelCallBudget`：每个任务一个，重试也计数；
- app 当前上限 **80 次/任务**；
- 规划、prompt skill、视觉 verify/extract 共用同一个 client/budget；
- 超出后抛 `ModelBudgetExceededException`（非网络错误，不重试）；
- host test：max=1，第二次调用被拒绝，used=2。
