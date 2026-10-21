# Reactive Agent Implementation Plan

> **For agentic workers:** Execute inline using executing-plans; the user has approved the design in the current conversation. Preserve all pre-existing workspace edits.

**Goal:** Default app execution continuously chooses one action from the current page until the original task is independently verified or explicitly handed off/stopped.

**Architecture:** Add a task-level reactive runner and a strict model adapter. Reuse DeviceApi, ActionExecutor and independent visual verification; replace the default App execution entry and fixed startup sleep.

**Tech Stack:** Java 17, Android, existing chat-completion client; no new dependency.

---

- [x] Add current-node selection and regression coverage in `brain/.../action/ObservedNodeSelector.java` and `ReactiveAgentTest.java`. A visible label may resolve to its closest clickable ancestor; a changed anchor must be rejected.
- [x] Add `brain/.../reactive/` decision/request/model/runner types and `DeepSeekReactiveModel.java`. Model output selects a current node index or one bounded action. Pass recent outcomes and block identical failed actions on an unchanged page. Independently verify Done; support allowed App changes, handoff, budget and cancellation.
- [x] Add outcome-only planning and connect `AgentRuntime` to the reactive runner. Final conditions derive from the user goal; planned stages are not executed. Keep browser final-page verification and account scope.
- [x] Remove the fixed 1200 ms startup delay. Require a new launch observation at the adapter boundary, and use bounded readiness polling in the runner.
- [x] Run the focused brain and capability adapter host checks and build the debug APK. Review the resulting diff and document implementation limits without claiming unobserved real-device outcomes.
