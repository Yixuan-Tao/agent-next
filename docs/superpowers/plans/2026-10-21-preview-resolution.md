# Preview Resolution Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise the Redmi Pad private display and all preview/frame paths to 1080×1920 at 360 dpi while preserving the current portrait layout and logical UI scale.

**Architecture:** Keep one canonical geometry in `DisplaySpec.standard()` and use it in the display creator, frame capture, preview rendering, and touch mapping. Update validators and frame size bounds alongside the geometry, then build and install the Debug APK and inspect the connected tablet preview.

**Tech Stack:** Java, Android SDK, Gradle, Android `ImageReader`/`VirtualDisplay`, ADB, Python desktop demo viewer.

---

## File map

| File | Responsibility |
| --- | --- |
| `device-api/src/main/java/agent/next/device/DisplaySpec.java` | Canonical width, height, and density for the private display. |
| `capability/src/main/java/agent/phone/executor/AndroidDisplayResources.java` | Allocate image reader and virtual display using the canonical geometry. |
| `capability/src/main/java/agent/phone/executor/DisplayLifecycle.java` | Check that the owned display matches the canonical geometry. |
| `capability/src/main/java/agent/phone/connection/DisplaySession.java` | Validate the dimensions recorded for the display session. |
| `capability/src/main/java/agent/phone/capability/BlockingDeviceApi.java` | Reject unsupported private display specifications with the updated contract. |
| `capability/src/main/java/agent/phone/connection/FrameEvidence.java` | Validate frame dimensions and set a bounded PNG payload limit that supports 1080×1920 frames. |
| `capability/src/main/java/agent/phone/executor/AndroidTargetObserver.java` | Keep its encoded frame buffer bounded by the updated evidence limit. |
| `app/src/main/java/agent/next/app/PreviewActivity.java` | Set the preview buffer size, fit the portrait frame, and map touch points into source coordinates. |
| `app/src/main/java/agent/next/app/DemoFramePublisher.java` | Capture debug monitor frames at the private display geometry. |
| `tools/demo-viewer.py` | Keep desktop viewer PNG and JSON limits compatible with the enlarged frame. |
| `device-api/src/test/java/agent/next/device/DeviceApiContract.java` and `device-api/src/test/java/agent/next/device/FakeDeviceApi.java` | Verify the default display and fake frames use the new geometry. |
| `capability/src/test/java/agent/phone/executor/DisplayLifecycleTest.java`, `capability/src/test/java/agent/phone/connection/FrameEvidenceTest.java`, `capability/src/test/java/agent/phone/connection/ConnectionCoordinatorTest.java`, `capability/src/test/java/agent/phone/executor/TargetLifecycleTest.java`, `tools/service-tests/agent/phone/connection/ShizukuBackendTest.java`, and related capability packet fixtures | Update old-size fixtures and assert the new geometry and coordinate bounds. |
| `device-api/README.md`, `capability/README.md`, and `app/README.md` | Update user-facing display/frame dimensions where they document the old default. |

## Tasks

### Task 1: Make the private display and frame contract 1080×1920 at 360 dpi

**Files:**
- Modify: `device-api/src/main/java/agent/next/device/DisplaySpec.java`
- Modify: `capability/src/main/java/agent/phone/executor/AndroidDisplayResources.java`
- Modify: `capability/src/main/java/agent/phone/executor/DisplayLifecycle.java`
- Modify: `capability/src/main/java/agent/phone/connection/DisplaySession.java`
- Modify: `capability/src/main/java/agent/phone/capability/BlockingDeviceApi.java`
- Modify: `capability/src/main/java/agent/phone/connection/FrameEvidence.java`
- Modify: `capability/src/main/java/agent/phone/executor/AndroidTargetObserver.java`

- [ ] **Step 1: Update the canonical geometry and all production validators.** Set `DisplaySpec.standard()` to `new DisplaySpec(1080, 1920, 360)`. Pass those values to both `ImageReader.newInstance` and `createVirtualDisplay`; validate the same values in `DisplayLifecycle`, `DisplaySession`, and `BlockingDeviceApi`.
- [ ] **Step 2: Update frame evidence dimensions and payload bounds.** Set frame width and height to 1080 and 1920. Raise the PNG limit from 4 MiB to 9 MiB, preserving a fixed upper bound; keep `AndroidTargetObserver` allocating only `FrameEvidence.MAX_PNG_BYTES`.
- [ ] **Step 3: Check remaining production geometry literals.** Run `rg -n '720|1280|240' device-api/src/main capability/src/main app/src/main tools/demo-viewer.py`; remove old geometry literals from production display/frame paths, leaving unrelated values only when their meaning is not display geometry.

### Task 2: Render and interact with the higher resolution preview

**Files:**
- Modify: `app/src/main/java/agent/next/app/PreviewActivity.java`
- Modify: `app/src/main/java/agent/next/app/DemoFramePublisher.java`
- Modify: `tools/demo-viewer.py`

- [ ] **Step 1: Use the canonical dimensions in the app preview.** Set `TextureView`'s default buffer size from `DisplaySpec.standard()` and use the same width and height in `fitVideo` so the existing portrait aspect ratio remains unchanged.
- [ ] **Step 2: Map takeover touches to source pixels.** Convert touch-up and touch-down coordinates using the canonical width and height, and clamp each coordinate to `[0, width - 1]` or `[0, height - 1]`. Preserve the existing tap-versus-swipe threshold and duration behavior.
- [ ] **Step 3: Capture debug frames at the same size.** Create `DemoFramePublisher`'s `ImageReader` from the display session dimensions; if `DisplayHandle` does not expose these fields, use `DisplaySpec.standard()` rather than introducing duplicate constants.
- [ ] **Step 4: Align viewer limits.** Raise `tools/demo-viewer.py`'s `MAX_PNG_BYTES` to 9 MiB and retain its existing JSON/base64 calculation from that constant.
- [ ] **Step 5: Review touch mapping on both axes.** Confirm the mapping uses the displayed `TextureView` width and height and source dimensions, with the right/bottom edge clamped to 1079/1919.

### Task 3: Update contracts, fixtures, and documentation

**Files:**
- Modify: `device-api/src/test/java/agent/next/device/DeviceApiContract.java`
- Modify: `device-api/src/test/java/agent/next/device/FakeDeviceApi.java`
- Modify: `capability/src/test/java/agent/phone/executor/DisplayLifecycleTest.java`
- Modify: `capability/src/test/java/agent/phone/connection/FrameEvidenceTest.java`
- Modify: `capability/src/test/java/agent/phone/connection/ConnectionCoordinatorTest.java`
- Modify: `capability/src/test/java/agent/phone/executor/TargetLifecycleTest.java`
- Modify: `tools/service-tests/agent/phone/connection/ShizukuBackendTest.java`
- Modify: other `capability/src/test/java/agent/phone/connection/*` fixtures constructing the standard display or frame
- Modify: `device-api/README.md`, `capability/README.md`, `app/README.md`

- [ ] **Step 1: Update display and frame contract fixtures.** Replace old standard-display fixture values in relevant device API, capability, and service tests with 1080×1920/360. Keep fixtures representing independent bounds or unrelated dimensions unchanged.
- [ ] **Step 2: Assert accepted and rejected frame geometry.** Update `FrameEvidenceTest` to accept 1080×1920 metadata, reject a mismatched width or height, and retain the existing rejection above `MAX_PNG_BYTES`.
- [ ] **Step 3: Verify touch boundary behavior.** Update target lifecycle tests so the last valid coordinate is 1079/1919 and the first out-of-bounds coordinate is 1080/1920.
- [ ] **Step 4: Update documented defaults.** Replace descriptions of the standard private display with 1080×1920 at 360 dpi and state that the logical UI scale is preserved.
- [ ] **Step 5: Search for stale old-size assumptions.** Run `rg -n '720|1280|240' device-api capability app tools docs`; review each remaining occurrence and update any one that still describes the standard source display.

### Task 4: Verify build and install on Redmi Pad Pro

**Files:**
- Build output: `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Run the focused host contract suites.** Run `./gradlew :device-api:test :capability:test`; expect both tasks to pass.
- [ ] **Step 2: Build the Debug APK.** Run `./gradlew :app:assembleDebug`; expect `BUILD SUCCESSFUL` and the APK at the path above.
- [ ] **Step 3: Install the Debug APK on the connected Redmi Pad Pro.** Use the already connected ADB device `172.16.14.12:44409` and install with replacement enabled; expect `Success`.
- [ ] **Step 4: Confirm the on-device display and preview.** Open the preview and verify the source reports 1080×1920 at 360 dpi, the portrait frame renders without cropping, and the desktop read-only viewer accepts the frame. Verify taps near the center and lower-right map to source coordinates inside `[0,1079] × [0,1919]`.

## Self-review

- Spec coverage: source dimensions and density, display lifecycle validation, frame evidence and bounded payloads, app preview scaling, touch coordinate mapping, debug frame publication, desktop viewer limits, tests, documentation, Debug build, and Redmi installation are all covered.
- Placeholder scan: no TBD/TODO or unspecified implementation steps; each task names exact files and actions.
- Consistency: all tasks use the single standard geometry 1080×1920/360, with matching final touch bounds 1079/1919 and matching 9 MiB PNG caps.
