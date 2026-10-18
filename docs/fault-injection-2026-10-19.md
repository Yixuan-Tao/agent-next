# Fault injection：网络中断（Android 16 平板）

日期：2026-10-19  
设备：Redmi Pad Pro / Android 16 / Shizuku 13.6  
场景：规划阶段断网

## 步骤

1. 打开 MainActivity；
2. 输入“查看京东首页...”，点击生成计划；
3. 立即 `adb shell svc wifi disable`；
4. 30 秒后恢复 wifi。

## 结果

```text
state: PLANNING · 规划中
state: FAILED · 规划失败: Software caused connection abort
```

- 没有进入执行；
- 没有设备动作；
- 没有伪造 DONE；
- 只记录了 HTTP 异常栈（无 API key/请求内容）。

## 结论

- 规划阶段网络故障 fail closed；
- 不应重试到无限，也不应使用缓存计划继续执行；
- 执行阶段的网络/模型故障、Shizuku 断连、target 丢失仍需继续注入测试。

## 待补

- 执行中模型 429/超时；
- Shizuku 服务被杀；
- target 窗口丢失；
- 20 分钟后台/Doze；
- Android 13。

## 执行阶段断网

步骤：计划已生成 → 执行 → 立即关闭 Wi-Fi 25 秒 → 恢复。

结果：

```text
state: RUNNING · 执行中
result: STOPPED · v1 stops on slow loop actions=2 attempts=1
```

- 没有进入支付/下单；
- 没有把网络异常当成 DONE；
- 当前 slow-loop policy 选择 STOP，任务安全结束；
- 不是“自动恢复成功”，需要重新发起任务。

后续可增加模型 429/超时退避与重试预算，但不能在未知副作用后自动重放动作。
