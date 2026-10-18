# Preview mirror / touch takeover smoke（Android 16 平板）

日期：2026-10-19  
设备：Redmi Pad Pro `2405CRPFDC`，Android 16 / API 36，Shizuku 13.6  
版本：`d67a0ed` + `PreviewActivity` 工作区改动  
性质：standalone 真机 smoke，还不是产品 runtime/UI

## 入口

debug 构建提供直接入口（release 仍为 `exported=false`）：

```sh
adb -s 9ec7fe43 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 9ec7fe43 shell am start -n agent.next.app/.PreviewActivity
```

`PreviewActivity` 自己连接 Shizuku、创建 private display、打开京东、在 TextureView
上启动 mirror；触摸事件归一化到 720×1280 后调用 capability-only
`AndroidPreviewController.userTap/userSwipe`。

## 结果

```text
AgentNextPreview: connect
AgentNextPreview: connected: display=d2f86329-... target=ed596f92-...
AgentNextPreview: preview mirror started; touch the video to inject user tap/swipe
AgentNextPreview: user tap 106,574
AgentNextPreview: target moved to main screen; use system UI, then return
AgentNextPreview: target returned to private display
AgentNextPreview: preview mirror started; touch the video to inject user tap/swipe
```

- **mirror 启动 PASS**：TextureView 显示京东私有 display 画面。
- **用户触摸 PASS**：屏幕点击经 PreviewActivity 映射为私有 display 的 `userTap`；
  京东从首页进入 11.11 会场，截图见下图。
- **主屏接管 PASS**：目标移到主屏后，京东 Activity 位于前台；返回私有 display
  后 mirror 重新启动成功。
- **返回后画面**：京东处于风控/活动页，截图接近黑屏；这是目标 App 当前页面和
  风控状态，不是 mirror 启动失败。未继续绕过风控。

![Preview mirror 启动](assets/preview-start.png)

![用户触摸后的京东页面](assets/preview-touch.png)

## 限制

- 本 smoke 由 `PreviewActivity` 自己持有 `BlockingDeviceApi`，尚未接入产品
  `AgentRuntime`；与 agent loop 并存、暂停/恢复和前台服务是 Phase 3。
- debug manifest 才允许 adb 直接 start；release `PreviewActivity` 不导出。
- 尚未在 Android 13 手机上重复；手机 gate 仍未完成。
- 未验证双指缩放、长按、输入法、拖动等复杂手势；当前只有 tap/swipe。
- 未验证返回后 mirror 画面持续刷新；本次返回页恰为风控页，未做进一步判断。
