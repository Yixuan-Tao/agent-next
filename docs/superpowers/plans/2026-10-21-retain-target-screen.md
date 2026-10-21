# Retain Target Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the last target app page available for preview and user input after the task ends, until a new task starts or the user releases it.

**Architecture:** Keep the existing `BlockingDeviceApi`, target, and private display alive in `AgentRuntime` after terminal task states; stop observation and leave the foreground service with a retained-screen notification. Add an explicit release action in `PreviewActivity`, which stops any active run and queues device cleanup on the existing worker.

**Tech Stack:** Android Java, existing Shizuku `BlockingDeviceApi`, `AgentTaskService`, and `PreviewActivity`.

---

### Task 1: Retain the session after terminal outcomes

**Files:**
- Modify: `app/src/main/java/agent/next/app/AgentRuntime.java`
- Modify: `app/src/main/java/agent/next/app/AgentTaskService.java`
- Modify: `brain/src/main/java/agent/next/brain/reactive/ReactiveSessionRunner.java`

- [ ] Change `doRun()` finalization to stop frame monitoring and keep the device session when `activeTarget` exists; close only sessions that never opened a target.
- [ ] Add an opt-in runner mode that retains the final target on non-success terminal outcomes; keep the current default cleanup behavior for existing callers.
- [ ] Change explicit task stop and runtime failure cleanup to preserve an opened target and update the foreground notification with retained-screen status.
- [ ] Keep `closeDevice()` for replacement by a new task, explicit release, and cleanup when target binding never succeeded.

### Task 2: Expose an explicit release action

**Files:**
- Modify: `app/src/main/java/agent/next/app/AgentRuntime.java`
- Modify: `app/src/main/java/agent/next/app/PreviewActivity.java`

- [ ] Add a release method that stops the active control, queues cleanup behind task work, releases the device, updates terminal status, and reports completion.
- [ ] Add a “释放画面” action in the preview UI; on success close the preview activity, otherwise show the error and keep the session available for retry.
- [ ] Preserve existing close-preview behavior: stop mirror, restore target audio policy, and keep the private display alive.

### Task 3: Verify the change

**Files:**
- Build: debug APK.

- [ ] Run the smallest applicable compile/build command.
- [ ] Review the final diff and confirm existing unrelated working-tree edits are not included in the change.
- [ ] Report device-only checks that still require installing and exercising the app.
