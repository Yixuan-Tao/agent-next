# Demo build 2026-10-21

本文件记录 2026-10-21 京东任务演示录屏对应的构建与设备，供复核。

| 项 | 值 |
|---|---|
| 日期 | 2026-10-21 |
| 源码标签 | `demo-2026-10-21` |
| 构建方式 | `./gradlew :capability:assembleDebug :app:assembleDebug` |
| APK 路径 | `app/build/outputs/apk/debug/app-debug.apk` |
| APK SHA-256 | `c652008c977c27b6fd74f639830b05d733bd5cd8ffa4fd77b1b52104c7d08ae4` |
| 安装包名 | `agent.next.app.debug` |
| 设备 | Redmi Pad Pro（2405CRPFDC）/ Android 16 / API 36 |
| 目标 App | 京东（`com.jingdong.app.mall`） |
| 录屏 | 设备屏幕录制，1728×1080，128.2 秒（待打码、剪辑后发布） |

## 说明

- 该 APK 为 Debug 构建；构建后源码树未再修改，标签指向的提交即录屏对应源码。
- 演示任务为“到京东买小米手环11”。Agent 在订单提交/支付边界完成独立核验后请求人工接管，不提交订单、不付款。
- 录屏中的订单核对页包含收件人信息，公开前必须打码；视频发布后在此补充链接。
- 正式提交候选包需由同一提交重新构建，并记录候选 APK 的 SHA-256。
