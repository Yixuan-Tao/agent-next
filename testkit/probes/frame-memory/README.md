# SharedMemory 合成数据平台探针

这是临时 Java 探针，验证公共 `android.os.SharedMemory` 与 `Parcel` 的传输内存语义。
输入为固定长度 4096 字节、按轮次生成的确定性字节序列；没有图片、截图或真实 App 内容。
只通过独立 `app_process` 执行，不安装 APK，不连接 UiAutomation，不创建显示，不操作 UI。

## 文件

- `FrameMemoryProbe.java`：探针及断言。
- `build.sh`：使用 `../qualification/build.env` 中的 JDK、API 36 SDK、D8 编译。
- `run.sh`：编译、推送临时 JAR、执行并保存输出，结束时清理设备上的该 JAR。
- `api-signatures.txt`：编译前用 SDK `javap` 核对的 `SharedMemory` 公共签名。
- `run.log`：实际设备执行结果。

## 复现

从仓库根目录执行（设备需要已授权 ADB）：

```sh
sh work/frame-memory-probe/run.sh \
  /Users/tommy/Documents/Codex/2026-10-15/xia/work/tools/platform-tools/adb \
  9ec7fe43
```

本工作环境的 ADB 命令需使用 `require_escalated`；主机编译不需要。
设备 `CLASSPATH` 只包含这个探针的 `probe.jar`。

## 验证步骤与结果

2026-10-18，实际设备 API 36：预热 1 轮后执行 100 轮。

1. `SharedMemory.create(name, 4096)`，断言长度一致。
2. `mapReadWrite` 写入合成字节，然后 `SharedMemory.unmap`。
3. `setProtect(PROT_READ)`，断言返回 `true`。
4. `writeToParcel(parcel, 0)`，断言 Parcel 包含 FD；复位 data position 后通过 `CREATOR.createFromParcel` 建立接收方对象。
5. 接收方 `mapReadOnly`，断言 ByteBuffer 只读且完整字节内容一致。
6. 接收方 `mapReadWrite` 必须失败；100 轮均返回 `ErrnoException`，`errno=1`（`EPERM`）。断言允许平台使用 `EACCES` 或 `EPERM` 表示拒绝，其他 errno 失败。
7. 在 `finally` 显式释放所有映射、关闭源和副本、`Parcel.recycle()`；其后验证已拷贝的接收字节数组仍与输入一致。
8. 每轮结束读取仅该探针进程的 `/proc/self/fd` 数量，未打印 FD 目标或其他进程数据。

结果：100/100 内容一致、只读、写映射拒绝；FD before=67、after=67、min=67、max=67。
探针允许计数偏差不超过 2，用于容纳运行时描述符变化；本次实际没有偏差。

## 验证边界

- 这是同一个 `app_process` 内的真实 Android `Parcel` FD 复制与 SharedMemory 映射验证，**没有验证跨进程 Binder 服务调用**。
- 合成字节传输不能证明真实截图生成、权限、Binder 服务实现、客户端渲染或模型消费链路正确。
- 保护在写映射解除后再设置；本探针不声称 `setProtect` 能撤销预先存在的写映射。
- `close()` 关闭持有对象的句柄；不能撤销接收方已经读取并复制到普通字节数组的数据。本探针不对 close 之后再调用 getSize/setProtect/map 的行为作任何推断或测试。
- 每轮 FD 数量是本进程的资源回收信号，不替代跨进程服务生命周期和取消路径测试。
