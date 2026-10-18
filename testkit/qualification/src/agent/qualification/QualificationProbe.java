package agent.qualification;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only shell diagnostic for the explicitly selected Android API 36 display. */
public final class QualificationProbe {
    private static final int MAX_NODES = ObservationBudget.MAX_LOOKUPS;
    private static final int MAX_TEXT_CODE_POINTS = 512;
    private final ProbeRequest request;

    QualificationProbe(ProbeRequest request) {
        this.request = request;
    }

    void checkDisplay() throws ReflectiveOperationException {
        Class<?> managerType = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object manager = managerType.getMethod("getInstance").invoke(null);
        Object info = managerType.getMethod("getDisplayInfo", int.class).invoke(manager, request.displayId);
        if (info == null) {
            throw new IllegalStateException("selected display no longer exists");
        }
        Class<?> infoType = Class.forName("android.view.DisplayInfo");
        SessionPolicy.checkDisplay(request.mode, request.foregroundSelected, Build.VERSION.SDK_INT,
                request.displayId, request.uniqueId, infoType.getField("displayId").getInt(info),
                (String) infoType.getField("uniqueId").get(info),
                (String) infoType.getField("name").get(info), infoType.getField("ownerUid").getInt(info));
    }

    private JSONObject inspect() throws ReflectiveOperationException, JSONException {
        long started = SystemClock.elapsedRealtimeNanos();
        checkDisplay();
        JSONObject result;
        try (ObservationSession session = new ObservationSession(request.displayId)) {
            checkDisplay();
            session.clearCache();
            List<AccessibilityWindowInfo> windows = session.selectedWindows(request.displayId);
            List<TargetWindow> targets = new ArrayList<TargetWindow>();
            ObservationBudget budget = new ObservationBudget();
            try {
                Set<Integer> focusedIds = collectTargets(windows, targets, budget);
                if (focusedIds.isEmpty()) {
                    throw new IllegalStateException("no focused target-package window on the selected display");
                }
                JSONArray windowRecords = new JSONArray();
                for (TargetWindow target : targets) {
                    JSONObject window = windowRecord(target.window);
                    AccessibilityNodeInfo root = target.root;
                    target.root = null;
                    window.put("nodes", inspectNodes(root, target.window.getId(), budget));
                    windowRecords.put(window);
                }
                checkDisplay();
                session.clearCache();
                Set<Integer> finalFocusedIds = focusedTargetWindows(session, budget);
                if (!focusedIds.equals(finalFocusedIds)) {
                    throw new IllegalStateException("focused target window changed during observation");
                }
                checkDisplay();
                result = new JSONObject();
                result.put("status", "ok");
                result.put("mode", request.mode == SessionPolicy.Mode.FOREGROUND ? "foreground" : "background");
                result.put("displayId", request.displayId);
                result.put("uniqueId", request.uniqueId);
                result.put("packageName", request.packageName);
                result.put("startElapsedRealtimeNanos", started);
                result.put("endElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos());
                result.put("maxNodes", MAX_NODES);
                result.put("maxTextCodePoints", MAX_TEXT_CODE_POINTS);
                result.put("nodeCount", budget.recorded);
                result.put("nodeLookupCount", budget.lookups());
                result.put("truncated", budget.truncated);
                result.put("windows", windowRecords);
            } finally {
                for (TargetWindow target : targets) {
                    recycle(target.root);
                }
                recycleWindows(windows);
            }
        }
        return result;
    }

    private Set<Integer> collectTargets(List<AccessibilityWindowInfo> windows, List<TargetWindow> targets,
            ObservationBudget budget) {
        Set<Integer> focusedIds = new HashSet<Integer>();
        for (AccessibilityWindowInfo window : windows) {
            checkWindowDisplay(window);
            budget.requireLookup();
            AccessibilityNodeInfo root = window.getRoot(0);
            if (root == null) {
                continue;
            }
            boolean retained = false;
            try {
                if (!isAllowed(root)) {
                    continue;
                }
                checkNodeWindow(root, window.getId());
                targets.add(new TargetWindow(window, root));
                retained = true;
                if (window.isFocused()) {
                    focusedIds.add(window.getId());
                }
            } finally {
                if (!retained) {
                    recycle(root);
                }
            }
        }
        return focusedIds;
    }

    Set<Integer> focusedTargetWindows(ObservationSession session, ObservationBudget budget) {
        List<AccessibilityWindowInfo> windows = session.selectedWindows(request.displayId);
        Set<Integer> focusedIds = new HashSet<Integer>();
        try {
            for (AccessibilityWindowInfo window : windows) {
                checkWindowDisplay(window);
                if (!window.isFocused()) {
                    continue;
                }
                budget.requireLookup();
                AccessibilityNodeInfo root = window.getRoot(0);
                try {
                    if (root != null && isAllowed(root)) {
                        checkNodeWindow(root, window.getId());
                        focusedIds.add(window.getId());
                    }
                } finally {
                    recycle(root);
                }
            }
        } finally {
            recycleWindows(windows);
        }
        return focusedIds;
    }

    private void checkWindowDisplay(AccessibilityWindowInfo window) {
        if (window.getDisplayId() != request.displayId) {
            throw new IllegalStateException("window does not belong to the selected display");
        }
    }

    private boolean isAllowed(AccessibilityNodeInfo node) {
        CharSequence packageName = node.getPackageName();
        String actual = packageName == null ? null : packageName.toString();
        if (!request.packageName.equals(actual)) {
            return false;
        }
        SessionPolicy.checkPackage(request.packageName, actual);
        return true;
    }

    private static void checkNodeWindow(AccessibilityNodeInfo node, int windowId) {
        if (node.getWindowId() != windowId) {
            throw new IllegalStateException("node window identity changed during observation");
        }
    }

    private JSONArray inspectNodes(AccessibilityNodeInfo root, int windowId, ObservationBudget budget)
            throws JSONException {
        JSONArray nodes = new JSONArray();
        ArrayDeque<NodeFrame> stack = new ArrayDeque<NodeFrame>();
        try {
            // The root was already charged when collectTargets obtained it.
            stack.addLast(new NodeFrame(root, -1));
            while (!stack.isEmpty()) {
                NodeFrame frame = stack.peekLast();
                if (!frame.recorded) {
                    if (!isAllowed(frame.node)) {
                        recycle(stack.removeLast().node);
                        continue;
                    }
                    checkNodeWindow(frame.node, windowId);
                    frame.index = budget.recorded++;
                    nodes.put(nodeRecord(frame.node, frame.index, frame.parentIndex));
                    frame.childCount = frame.node.getChildCount();
                    frame.recorded = true;
                }
                if (frame.nextChild >= frame.childCount) {
                    recycle(stack.removeLast().node);
                    continue;
                }
                // Bound child lookups as well as output nodes, including null or foreign-package children.
                if (!budget.reserve()) {
                    break;
                }
                AccessibilityNodeInfo child = frame.node.getChild(frame.nextChild++, 0);
                if (child != null) {
                    stack.addLast(new NodeFrame(child, frame.index));
                }
            }
        } finally {
            while (!stack.isEmpty()) {
                recycle(stack.removeLast().node);
            }
        }
        return nodes;
    }

    private JSONObject nodeRecord(AccessibilityNodeInfo node, int index, int parentIndex)
            throws JSONException {
        JSONObject record = new JSONObject();
        record.put("index", index);
        record.put("parentIndex", parentIndex < 0 ? JSONObject.NULL : parentIndex);
        record.put("windowId", node.getWindowId());
        record.put("packageName", request.packageName);
        record.put("editable", node.isEditable());
        record.put("focused", node.isFocused());
        boolean password = node.isPassword();
        record.put("password", password);
        record.put("clickable", node.isClickable());
        record.put("scrollable", node.isScrollable());
        record.put("visibleToUser", node.isVisibleToUser());
        record.put("enabled", node.isEnabled());
        record.put("bounds", nodeBounds(node));
        putLimited(record, "className", node.getClassName());
        putLimited(record, "viewId", node.getViewIdResourceName());
        if (password) {
            record.put("text", "[REDACTED]");
            record.put("contentDescription", "[REDACTED]");
            record.put("textTruncated", false);
            record.put("contentDescriptionTruncated", false);
        } else {
            putLimited(record, "text", node.getText());
            putLimited(record, "contentDescription", node.getContentDescription());
        }
        record.put("redacted", password);
        return record;
    }

    private static JSONObject windowRecord(AccessibilityWindowInfo window) throws JSONException {
        JSONObject record = new JSONObject();
        record.put("windowId", window.getId());
        record.put("displayId", window.getDisplayId());
        record.put("type", window.getType());
        record.put("layer", window.getLayer());
        record.put("focused", window.isFocused());
        record.put("active", window.isActive());
        record.put("accessibilityFocused", window.isAccessibilityFocused());
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        record.put("bounds", boundsRecord(bounds));
        return record;
    }

    private static JSONObject nodeBounds(AccessibilityNodeInfo node) throws JSONException {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return boundsRecord(bounds);
    }

    private static JSONObject boundsRecord(Rect bounds) throws JSONException {
        JSONObject record = new JSONObject();
        record.put("left", bounds.left);
        record.put("top", bounds.top);
        record.put("right", bounds.right);
        record.put("bottom", bounds.bottom);
        return record;
    }

    private static void putLimited(JSONObject record, String key, CharSequence value) throws JSONException {
        if (value == null) {
            record.put(key, JSONObject.NULL);
            record.put(key + "Truncated", false);
            return;
        }
        String text = value.toString();
        boolean truncated = text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS;
        if (truncated) {
            text = text.substring(0, text.offsetByCodePoints(0, MAX_TEXT_CODE_POINTS));
        }
        record.put(key, text);
        record.put(key + "Truncated", truncated);
    }

    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo node) {
        if (node != null) {
            node.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private static void recycleWindows(List<AccessibilityWindowInfo> windows) {
        for (AccessibilityWindowInfo window : windows) {
            window.recycle();
        }
    }

    private static final class TargetWindow {
        private final AccessibilityWindowInfo window;
        private AccessibilityNodeInfo root;

        TargetWindow(AccessibilityWindowInfo window, AccessibilityNodeInfo root) {
            this.window = window;
            this.root = root;
        }
    }

    private static final class NodeFrame {
        private final AccessibilityNodeInfo node;
        private final int parentIndex;
        private int index;
        private int nextChild;
        private int childCount;
        private boolean recorded;

        NodeFrame(AccessibilityNodeInfo node, int parentIndex) {
            this.node = node;
            this.parentIndex = parentIndex;
        }
    }

    static final class ObservationSession implements AutoCloseable {
        private final HandlerThread callbacks;
        private UiAutomation automation;
        private boolean connectAttempted;

        ObservationSession(int displayId) throws ReflectiveOperationException {
            callbacks = new HandlerThread("QualificationProbeCallbacks");
            try {
                callbacks.start();
                Object connection = Class.forName("android.app.UiAutomationConnection")
                        .getConstructor().newInstance();
                Constructor<?> constructor = UiAutomation.class.getDeclaredConstructor(int.class,
                        Looper.class, Class.forName("android.app.IUiAutomationConnection"));
                constructor.setAccessible(true);
                automation = (UiAutomation) constructor.newInstance(displayId, callbacks.getLooper(), connection);
                connectAttempted = true;
                invokeAutomation("connect", new Class<?>[]{int.class},
                        UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
                AccessibilityServiceInfo info = automation.getServiceInfo();
                if (info == null) {
                    throw new IllegalStateException("automation service configuration is unavailable");
                }
                info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                automation.setServiceInfo(info);
            } catch (ReflectiveOperationException | RuntimeException | Error failure) {
                try {
                    close();
                } catch (ReflectiveOperationException | RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        UiAutomation automation() {
            return automation;
        }

        void clearCache() {
            if (!automation.clearCache()) {
                throw new IllegalStateException("accessibility observation cache could not be cleared");
            }
        }

        List<AccessibilityWindowInfo> selectedWindows(int displayId) {
            SparseArray<List<AccessibilityWindowInfo>> all = automation.getWindowsOnAllDisplays();
            if (all == null || all.get(displayId) == null || all.get(displayId).isEmpty()) {
                throw new IllegalStateException("no windows on the selected display");
            }
            return all.get(displayId);
        }

        private void invokeAutomation(String methodName, Class<?>[] signature, Object... args)
                throws ReflectiveOperationException {
            Method method = UiAutomation.class.getMethod(methodName, signature);
            method.invoke(automation, args);
        }

        @Override
        public void close() throws ReflectiveOperationException {
            try {
                if (connectAttempted) {
                    connectAttempted = false;
                    invokeAutomation("disconnect", new Class<?>[0]);
                }
            } finally {
                callbacks.quitSafely();
            }
        }
    }

    private static void execute(ProbeRequest request) {
        int status = 0;
        try {
            JSONObject observation = new QualificationProbe(request).inspect();
            System.out.println(observation.toString());
        } catch (Throwable failure) {
            reportFailure(failure);
            status = 1;
        }
        System.exit(status);
    }

    private static void reportFailure(Throwable failure) {
        while (failure instanceof InvocationTargetException && failure.getCause() != null) {
            failure = failure.getCause();
        }
        System.err.println("error=" + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }

    public static void main(String[] args) {
        final ProbeRequest request;
        try {
            // Malformed requests must fail before touching any Android API or starting threads.
            request = ProbeRequest.parse(args);
        } catch (Throwable failure) {
            reportFailure(failure);
            System.exit(1);
            return;
        }
        try {
            Looper.prepare();
            // app_process lacks application bootstrap; API 36 uses this test hook for accessibility queries.
            Looper.class.getMethod("setMainLooperForTest", Looper.class).invoke(null, Looper.myLooper());
            new Thread(new Runnable() {
                @Override
                public void run() {
                    execute(request);
                }
            }, "QualificationProbeCommand").start();
            Looper.loop();
        } catch (Throwable failure) {
            reportFailure(failure);
            System.exit(1);
        }
    }
}
