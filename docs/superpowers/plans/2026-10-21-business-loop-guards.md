# Business Loop Guards Implementation Plan

**Goal:** 为实时页面执行循环增加往返、无进展和重复副作用控制。

**Architecture:** ReactiveLoopGuard 管理页面轨迹和预算，EffectLedger 管理任务内副作用记录；ReactiveSessionRunner 调度独立核验，DeepSeekReactiveModel 输出语义 effect 并读取持久记录。

**Tech Stack:** Java 17，现有主机测试与 SubgoalVerifier，无新增依赖。

- [x] 在 ReactiveSessionTest 增加页面往返与不同等待动作的失败复现，运行 tools/test-brain.sh；旧实现因往返耗尽总预算而失败。
- [x] 新增 ReactiveLoopGuard，并将动作后观察和派发前拦截接入 ReactiveSessionRunner。
- [x] 新增 BusinessEffect/EffectLedger，扩展 Act 和 ReactiveRequest，更新模型 JSON 解析及提示词，连接独立成功/未生效核验。
- [x] 补充副作用成功、未知、一次重试、跨页与超时回归，运行 tools/test-brain.sh；30 组检查通过。
- [x] 更新 docs/reactive-agent-2026-10-21.md，检查本次文件差异与 git diff --check；离线 Android APK 构建通过。
