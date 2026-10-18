# Android 13 兼容性 spike（Beta 基线）

状态：**代码基线通过；Android 13 真机待连接验收**。

## 决策

Beta 的 `minSdk` 定为 **33（Android 13）**，`compileSdk/targetSdk` 保持 36。  
第二台手机按 Android 13 验收；若实际低于 33，需要另开兼容阶段或更换设备。

## 已改

- `app` / `capability` 的 `minSdk 36 → 33`。
- `app` Java 编译级别改为 17（`brain` 的 records 需要；仅 Java 8 会在 D8 record desugaring 失败）。
- `ProtocolPolicy` 接受 API 33–36 + shell UID 2000；原来只接受 36。
- `ShellService.getCapabilities` 增加 `preview_mirror` 布尔值，由 shell 进程内的
  `PreviewCapabilities.probe()` 计算；`Capabilities`/`BlockingDeviceApi` 映射为
  `DeviceFeature.PREVIEW_MIRROR`。
- app manifest 增加 launcher `<queries>`，修复 Android 11+ package visibility 导致
  `getPackageInfo(JD)` 返回未安装的问题。
- app 增加 `mode=compat`：只连接/创建 display/打开目标/observe/frame/release，
  不做输入；用于 Android 13 手机的低风险准入。

## Android 16 平板结果

```text
compat: android=16 sdk=36 model=2405CRPFDC
compat: PreviewCapabilities{config=true, mirror=false, ... usable=false}
compat: device=DeviceCapabilities{protocol=3, api=36, supported=true,
  features=[..., PREVIEW_MIRROR]} previewMirror=true
compat: observe nodes=96 package=com.jingdong.app.mall
compat: frame 720x1280 png=502442
COMPAT PASS
```

说明：app 进程内的 `PreviewCapabilities` 反射可能被 hidden API 过滤，看到 `mirror=false`；
**以 shell service 的 `preview_mirror=true` 为准**。真正 mirror 还要在 Android 13/16 上做
Surface 视频与触摸验收，不能仅凭反射结论。

## Android 13 手机待跑

连接手机后：

```sh
adb -s <phone> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <phone> shell am start -n agent.next.app/.ShellActivity \
  --es mode compat --es package com.jingdong.app.mall
adb -s <phone> logcat -s AgentNextSmoke:I
```

需要记录：

- API/ROM/Shizuku；
- `previewMirror` 是否为 true；
- display/target/observe/frame 是否 PASS；
- 若 mirror 不可用，按计划走 capture-preview fallback 或主屏接管。

## 未完成

- Android 13 真机的 Shizuku 服务、display、target、frame 实测。
- Android 13 的 mirror Surface/触摸实测与 fallback 选择。
- 前台服务类型（API 33 `dataSync` vs API 34+ `specialUse`）尚未接入；Phase 3 再做。
- 第二台手机的正式 Beta 验收。
