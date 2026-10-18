# 设备端 Parcel 失败注入

2026-10-18，Redmi Pad Pro / Android 16，当前调试 APK。探针通过 `app_process` 加载 APK 类，在真实 `Parcel`/`SharedMemory` 上构造并解码帧包。

## 方法

有效帧包先 `Bundle.writeToParcel`，再 `Bundle.CREATOR.createFromParcel`，让接收方获得真实的 FD 副本；每个用例后检查 `/proc/self/fd` 数量，确认解码器关闭了帧内存。

## 结果

```
baseline_fd=67
valid=PASS fd=67
missing_png_key=PASS fd=67
wrong_png_type=PASS fd=67
unknown_png_key=PASS fd=67
stale_sequence=PASS fd=67
byte_length_mismatch=PASS fd=67
corrupt_png_header=PASS fd=67
writable_memory=PASS fd=67
oversized_length=PASS fd=67
valid_after_failures=PASS fd=67
ParcelFailureDiagnostic: PASS
```

## 边界

- 这是客户端解码器在真实 Android Parcel 上的失败注入；不是跨进程恶意服务，也没有强制 `mapReadOnly` 的 `ENOMEM`（host stub 覆盖）。
- 未测试超过 Binder 事务上限的包；帧大小目前约 0.5 MB。
- 原始输出：`run.log`。
