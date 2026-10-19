# 20 分钟前台服务 soak

开始：2026-10-19 13:05 ACDT（Asia/Adelaide 13:05）  
设备：Redmi Pad Pro / Android 16  
入口：debug `MainActivity --ez soak true` → `AgentTaskService.SOAK`  
检查点：5 / 10 / 20 分钟

## 启动状态

```text
ServiceRecord act=agent.next.app.SOAK
isForeground=true foregroundId=41 types=0x40000001
```

## 结果

（待 20 分钟检查后填写；不会把“启动成功”当成 20 分钟通过。）

## 限制

- 这是前台服务/进程 soak，不是完整 agent 任务并发；
- 不验证模型/Shizuku 持续执行；
- 不替代 Chain B/Android 13。

## 结果

```text
13:05 start: AgentTaskService act=SOAK, isForeground=true, types=0x40000001
13:10 +5min: isForeground=true, app process alive
13:15 +10min: isForeground=true, app process alive
13:25 +20min: AgentTaskService absent (soak timer stopSelf), app process still present
```

- 20 分钟内前台服务和进程未崩溃/未被系统回收；
- 20 分钟定时停止生效；
- 这是 **foreground service soak**，不是完整 agent 任务并发，也不验证模型/Shizuku 持续执行；
- 不替代 Chain B / Android 13。
