# 设置与隐私入口

更新：2026-10-20。`SettingsActivity` 已实现；正式入口为 `MainActivity` → “设置与隐私”，release 不导出该 Activity。

## 当前功能

- 通过系统文件选择器导入模型 JSON（`base_url`、`model`、`api_key`），最大 65,536 bytes；校验后保存加密私有副本。
- 显示加密配置是否可用，不显示 key。
- 显示 episodic memory 数量，并可清除事件。
- 展示本地加密和数据使用的基础说明。

导入只删除 app 私有目录中待迁移的旧明文配置，不会删除选择器中的外部源文件。用户需自行管理源文件及任何导出副本。

## 待完成的产品行为

[纯 GUI Beta](pure-gui-beta.md) 要求按任务隔离与删除、事件三十天保留、暂停检查点二十四小时、级联清理及恢复状态展示。这些不能由现有“清除 episodic memory”按钮代替。

当前 UI 中的简短隐私文案不是完整安全保证。模型请求、runtime 日志和 debug 截图的实际数据流与迁移要求见 [隐私说明](privacy.md)。

## 历史设备记录

Android 16 上曾验证设置页打开、事件计数和清除加密事件文件。它只证明该入口行为，不代表新版保留策略、跨任务隔离与凭据采集控制通过。设备测试需从 MainActivity 进入；debug-only 直接入口不作为 release 能力。
