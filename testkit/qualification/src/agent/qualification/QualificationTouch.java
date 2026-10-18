package agent.qualification;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import org.json.JSONObject;

/** One explicitly requested touch gesture using UID-targeted API 36 injection. */
public final class QualificationTouch {
    interface EventDispatcher {
        void emit(int action, float x, float y, int offsetMillis) throws Exception;
    }

    private final TouchRequest request;
    private final QualificationProbe probe;

    private QualificationTouch(TouchRequest request) {
        this.request = request;
        probe = new QualificationProbe(request.probe);
    }

    static void dispatchEvents(TouchRequest request, EventDispatcher dispatcher) throws Exception {
        dispatcher.emit(MotionEvent.ACTION_DOWN, request.x1, request.y1, 0);
        if ("swipe".equals(request.action)) {
            int moves = request.durationMillis / 50;
            for (int i = 1; i <= moves; i++) {
                int offset = request.durationMillis * i / (moves + 1);
                double fraction = (double) offset / request.durationMillis;
                float x = (float) (request.x1 + ((double) request.x2 - request.x1) * fraction);
                float y = (float) (request.y1 + ((double) request.y2 - request.y1) * fraction);
                dispatcher.emit(MotionEvent.ACTION_MOVE, x, y, offset);
            }
        }
        dispatcher.emit(MotionEvent.ACTION_UP, request.x2, request.y2, request.durationMillis);
    }

    private JSONObject touch() throws Exception {
        final DispatchState state = new DispatchState();
        long started = SystemClock.elapsedRealtimeNanos();
        try {
            probe.checkDisplay();
            final int targetUid = resolveTargetUid();
            final TargetedInjector injector = new TargetedInjector(targetUid);
            JSONObject result;
            try (final QualificationProbe.ObservationSession session =
                    new QualificationProbe.ObservationSession(request.probe.displayId)) {
                final TargetSnapshot original = readTarget(session);
                try {
                    dispatchEvents(request, new EventDispatcher() {
                        @Override
                        public void emit(int action, float x, float y, int offsetMillis) throws Exception {
                            if (state.downTime >= 0) {
                                long remaining = state.downTime + offsetMillis - SystemClock.uptimeMillis();
                                if (remaining > 0) {
                                    SystemClock.sleep(remaining);
                                }
                            }
                            TargetSnapshot current = checkIdentity(session, original, targetUid);
                            TouchRequest.checkPoint(x, y, current.bounds.left, current.bounds.top,
                                    current.bounds.right, current.bounds.bottom, current.width, current.height);
                            long eventTime = SystemClock.uptimeMillis();
                            if (state.downTime < 0) {
                                state.downTime = eventTime;
                            }
                            state.lastX = x;
                            state.lastY = y;
                            boolean accepted = inject(injector, state, action, x, y, eventTime);
                            if (!accepted) {
                                throw new IllegalStateException("UID-targeted touch event was not accepted");
                            }
                            if (action == MotionEvent.ACTION_UP) {
                                state.upAccepted = true;
                            }
                        }
                    });
                    result = receipt(state, targetUid, started);
                } catch (Exception | Error failure) {
                    cancelOriginal(session, original, injector, state, failure);
                    throw failure;
                }
            }
            return result;
        } catch (Exception | Error failure) {
            if (state.dispatchAttempted) {
                Throwable cause = failure;
                while (cause instanceof InvocationTargetException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                throw new IllegalStateException("touch dispatch was attempted (acceptedEvents="
                        + state.acceptedEvents + ", lastAccepted="
                        + (state.lastAccepted == null ? "unknown" : state.lastAccepted.toString())
                        + ", cancelAttempted=" + state.cancelAttempted + ", cancelAccepted="
                        + (state.cancelAccepted == null ? "unknown" : state.cancelAccepted.toString())
                        + "); acceptance means injection only; possible input occurred; "
                        + "do not retry automatically: " + cause.getClass().getSimpleName() + ": "
                        + cause.getMessage(), failure);
            }
            throw failure;
        }
    }

    private TargetSnapshot checkIdentity(QualificationProbe.ObservationSession session,
            TargetSnapshot expected, int targetUid) throws ReflectiveOperationException {
        TargetSnapshot current = readTarget(session);
        if (!expected.bounds.equals(current.bounds) || expected.width != current.width
                || expected.height != current.height) {
            throw new IllegalStateException("target window bounds or display dimensions changed during touch");
        }
        if (resolveTargetUid() != targetUid) {
            throw new IllegalStateException("target package UID changed during touch");
        }
        probe.checkDisplay();
        return current;
    }

    private TargetSnapshot readTarget(QualificationProbe.ObservationSession session)
            throws ReflectiveOperationException {
        probe.checkDisplay();
        session.clearCache();
        int[] size = displaySize();
        SparseArray<List<AccessibilityWindowInfo>> all = session.automation().getWindowsOnAllDisplays();
        try {
            List<AccessibilityWindowInfo> windows = all == null ? null : all.get(request.probe.displayId);
            if (windows == null || windows.isEmpty()) {
                throw new IllegalStateException("no windows on the selected display");
            }
            int matches = 0;
            Rect bounds = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getDisplayId() != request.probe.displayId) {
                    throw new IllegalStateException("window does not belong to the selected display");
                }
                if (window.isFocused() && window.getId() != request.windowId) {
                    throw new IllegalStateException("a different window is focused on the selected display");
                }
                if (window.getId() != request.windowId) {
                    continue;
                }
                matches++;
                if (!window.isFocused()) {
                    throw new IllegalStateException("expected window is not focused");
                }
                AccessibilityNodeInfo root = window.getRoot(0);
                try {
                    if (root == null) {
                        throw new IllegalStateException("expected window root is not readable");
                    }
                    // Only package identity is read; this tool never traverses the root or reads text.
                    CharSequence packageName = root.getPackageName();
                    SessionPolicy.checkPackage(request.probe.packageName,
                            packageName == null ? null : packageName.toString());
                    bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    if (window.getId() != request.windowId || window.getDisplayId() != request.probe.displayId
                            || !window.isFocused()) {
                        throw new IllegalStateException("target window identity changed while reading its bounds");
                    }
                } finally {
                    recycle(root);
                }
            }
            if (matches != 1 || bounds == null) {
                throw new IllegalStateException("expected window must be unique on the selected display");
            }
            request.checkBounds(bounds.left, bounds.top, bounds.right, bounds.bottom, size[0], size[1]);
            probe.checkDisplay();
            int[] confirmedSize = displaySize();
            if (size[0] != confirmedSize[0] || size[1] != confirmedSize[1]) {
                throw new IllegalStateException("display dimensions changed while reading the target window");
            }
            return new TargetSnapshot(bounds, size[0], size[1]);
        } finally {
            recycleWindows(all);
        }
    }

    private int[] displaySize() throws ReflectiveOperationException {
        Class<?> managerType = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object manager = managerType.getMethod("getInstance").invoke(null);
        Object info = managerType.getMethod("getDisplayInfo", int.class).invoke(manager, request.probe.displayId);
        if (info == null) {
            throw new IllegalStateException("selected display no longer exists");
        }
        Class<?> infoType = Class.forName("android.view.DisplayInfo");
        SessionPolicy.checkDisplay(request.probe.mode, request.probe.foregroundSelected,
                android.os.Build.VERSION.SDK_INT, request.probe.displayId, request.probe.uniqueId,
                infoType.getField("displayId").getInt(info), (String) infoType.getField("uniqueId").get(info),
                (String) infoType.getField("name").get(info), infoType.getField("ownerUid").getInt(info));
        return new int[]{infoType.getField("logicalWidth").getInt(info),
                infoType.getField("logicalHeight").getInt(info)};
    }

    private int resolveTargetUid() throws ReflectiveOperationException {
        // Official API 36 AIDL: getPackageUid(String packageName, long flags, int userId).
        // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/java/android/content/pm/IPackageManager.aidl
        Object manager = Class.forName("android.app.AppGlobals").getMethod("getPackageManager").invoke(null);
        if (manager == null) {
            throw new IllegalStateException("system package manager is unavailable");
        }
        Class<?> managerType = Class.forName("android.content.pm.IPackageManager");
        int targetUid = ((Integer) managerType.getMethod("getPackageUid", String.class, long.class, int.class)
                .invoke(manager, request.probe.packageName, 0L, 0)).intValue();
        TouchRequest.checkTargetUid(targetUid);
        return targetUid;
    }

    private boolean inject(TargetedInjector injector, DispatchState state, int action,
            float x, float y, long eventTime) throws ReflectiveOperationException {
        MotionEvent event = MotionEvent.obtain(state.downTime, eventTime, action, x, y, 0);
        try {
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            injector.setDisplayId.invoke(event, request.probe.displayId);
            if (((Integer) injector.getDisplayId.invoke(event)).intValue() != request.probe.displayId
                    || event.getSource() != InputDevice.SOURCE_TOUCHSCREEN) {
                throw new IllegalStateException("touch event source or display routing does not match the request");
            }
            boolean cancel = action == MotionEvent.ACTION_CANCEL;
            if (cancel) {
                state.cancelAttempted = true;
                state.cancelAccepted = null;
            } else {
                if (!state.dispatchAttempted) {
                    state.dispatchStarted = SystemClock.elapsedRealtimeNanos();
                }
                state.dispatchAttempted = true;
                state.lastAccepted = null;
            }
            boolean accepted;
            try {
                accepted = ((Boolean) injector.inject.invoke(injector.manager, event,
                        injector.waitForResult, injector.targetUid)).booleanValue();
            } finally {
                if (!cancel) {
                    state.dispatchEnded = SystemClock.elapsedRealtimeNanos();
                }
            }
            if (cancel) {
                state.cancelAccepted = Boolean.valueOf(accepted);
            } else {
                state.lastAccepted = Boolean.valueOf(accepted);
                if (accepted) {
                    state.acceptedEvents++;
                }
            }
            return accepted;
        } finally {
            event.recycle();
        }
    }

    private void cancelOriginal(QualificationProbe.ObservationSession session, TargetSnapshot original,
            TargetedInjector injector, DispatchState state, Throwable failure) {
        if (!state.dispatchAttempted || state.upAccepted) {
            return;
        }
        try {
            try {
                checkIdentity(session, original, injector.targetUid);
            } catch (Exception | Error changed) {
                failure.addSuppressed(changed);
            }
            // A changed focus must stop normal events. Cleanup still targets only the original
            // display identity and UID; if that display is gone/reused or UID changed, skip cleanup.
            probe.checkDisplay();
            if (resolveTargetUid() != injector.targetUid) {
                throw new IllegalStateException("original package UID changed; touch cleanup was skipped");
            }
            if (!inject(injector, state, MotionEvent.ACTION_CANCEL, state.lastX, state.lastY,
                    SystemClock.uptimeMillis())) {
                throw new IllegalStateException("original UID-targeted ACTION_CANCEL was not accepted");
            }
        } catch (Exception | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private JSONObject receipt(DispatchState state, int targetUid, long started) throws Exception {
        JSONObject result = new JSONObject();
        result.put("status", "injected");
        result.put("dispatchAttempted", state.dispatchAttempted);
        result.put("accepted", true);
        result.put("acceptanceMeaning", "input injection only; application outcome was not checked");
        result.put("mode", request.probe.mode == SessionPolicy.Mode.FOREGROUND ? "foreground" : "background");
        result.put("displayId", request.probe.displayId);
        result.put("uniqueId", request.probe.uniqueId);
        result.put("packageName", request.probe.packageName);
        result.put("windowId", request.windowId);
        result.put("targetUid", targetUid);
        result.put("action", request.action);
        result.put("x1", request.x1);
        result.put("y1", request.y1);
        result.put("x2", request.x2);
        result.put("y2", request.y2);
        result.put("durationMillis", request.durationMillis);
        result.put("acceptedEvents", state.acceptedEvents);
        result.put("startElapsedRealtimeNanos", started);
        result.put("dispatchStartElapsedRealtimeNanos", state.dispatchStarted);
        result.put("dispatchEndElapsedRealtimeNanos", state.dispatchEnded);
        result.put("endElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos());
        return result;
    }

    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo root) {
        if (root != null) {
            root.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private static void recycleWindows(SparseArray<List<AccessibilityWindowInfo>> all) {
        if (all != null) {
            for (int i = 0; i < all.size(); i++) {
                List<AccessibilityWindowInfo> windows = all.valueAt(i);
                if (windows != null) {
                    for (AccessibilityWindowInfo window : windows) {
                        window.recycle();
                    }
                }
            }
        }
    }

    private static final class TargetedInjector {
        private final Object manager;
        private final Method inject;
        private final Method setDisplayId;
        private final Method getDisplayId;
        private final int waitForResult;
        private final int targetUid;

        TargetedInjector(int targetUid) throws ReflectiveOperationException {
            this.targetUid = targetUid;
            Class<?> managerType = Class.forName("android.hardware.input.InputManagerGlobal");
            manager = managerType.getMethod("getInstance").invoke(null);
            // This exact 3-argument API calls injectInputEventToTarget; TARGET_MISMATCH fails.
            // No untargeted overload or shell-input fallback is permitted.
            // https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-qpr2-release/core/java/android/hardware/input/InputManagerGlobal.java
            inject = managerType.getMethod("injectInputEvent", InputEvent.class, int.class, int.class);
            setDisplayId = MotionEvent.class.getMethod("setDisplayId", int.class);
            getDisplayId = MotionEvent.class.getMethod("getDisplayId");
            waitForResult = Class.forName("android.os.InputEventInjectionSync")
                    .getField("WAIT_FOR_RESULT").getInt(null);
        }
    }

    private static final class TargetSnapshot {
        private final Rect bounds;
        private final int width;
        private final int height;

        TargetSnapshot(Rect bounds, int width, int height) {
            this.bounds = bounds;
            this.width = width;
            this.height = height;
        }
    }

    private static final class DispatchState {
        private boolean dispatchAttempted;
        private Boolean lastAccepted;
        private int acceptedEvents;
        private boolean cancelAttempted;
        private Boolean cancelAccepted;
        private boolean upAccepted;
        private long downTime = -1;
        private long dispatchStarted;
        private long dispatchEnded;
        private float lastX;
        private float lastY;
    }

    public static void main(String[] args) {
        final TouchRequest request;
        try {
            // Parse before Android bootstrap, connecting UiAutomation, or creating threads.
            request = TouchRequest.parse(args);
        } catch (Throwable failure) {
            QualificationCommand.reportFailure(failure);
            System.exit(1);
            return;
        }
        QualificationCommand.start(new QualificationCommand.Operation() {
            @Override
            public JSONObject run() throws Exception {
                return new QualificationTouch(request).touch();
            }
        });
    }
}
