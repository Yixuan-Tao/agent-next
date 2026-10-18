package agent.qualification;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;

/** One ACTION_SET_TEXT dispatch followed by exact readback on the same field. */
public final class QualificationInput {
    private static final long READBACK_TIMEOUT_NANOS = 2_000_000_000L;
    private static final long READBACK_POLL_MILLIS = 50;
    private final InputRequest request;
    private final QualificationProbe probe;

    private QualificationInput(InputRequest request) {
        this.request = request;
        probe = new QualificationProbe(request.probe);
    }

    private JSONObject replaceText() throws Exception {
        boolean dispatchAttempted = false;
        Boolean accepted = null;
        try {
            long started = SystemClock.elapsedRealtimeNanos();
            probe.checkDisplay();
            JSONObject result;
            try (QualificationProbe.ObservationSession session =
                    new QualificationProbe.ObservationSession(request.probe.displayId)) {
                ObservationBudget budget = new ObservationBudget();
                probe.checkDisplay();
                session.clearCache();
                Set<Integer> focusedIds = probe.focusedTargetWindows(session, budget);
                if (!focusedIds.contains(request.windowId)) {
                    throw new IllegalStateException("expected window is not a focused target-package window");
                }
                AccessibilityNodeInfo target = findUniqueTarget(session, budget);
                try {
                    refreshTarget(target, budget);
                    checkIdentity(session, budget, focusedIds);
                    Bundle arguments = new Bundle();
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            request.text);
                    long dispatchStarted = SystemClock.elapsedRealtimeNanos();
                    dispatchAttempted = true;
                    accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                    long dispatchEnded = SystemClock.elapsedRealtimeNanos();
                    if (!accepted.booleanValue()) {
                        throw new IllegalStateException("ACTION_SET_TEXT was not accepted");
                    }
                    String actualText = awaitExactReadback(target, session, budget, focusedIds,
                            dispatchEnded + READBACK_TIMEOUT_NANOS);
                    result = new JSONObject();
                    result.put("status", "verified");
                    result.put("accepted", true);
                    result.put("verified", true);
                    result.put("mode", request.probe.mode == SessionPolicy.Mode.FOREGROUND ? "foreground" : "background");
                    result.put("displayId", request.probe.displayId);
                    result.put("uniqueId", request.probe.uniqueId);
                    result.put("packageName", request.probe.packageName);
                    result.put("windowId", request.windowId);
                    result.put("viewId", request.viewId);
                    result.put("startElapsedRealtimeNanos", started);
                    result.put("dispatchStartElapsedRealtimeNanos", dispatchStarted);
                    result.put("dispatchEndElapsedRealtimeNanos", dispatchEnded);
                    result.put("endElapsedRealtimeNanos", SystemClock.elapsedRealtimeNanos());
                    result.put("actualText", actualText);
                } finally {
                    recycle(target);
                }
            }
            return result;
        } catch (Exception | Error failure) {
            if (dispatchAttempted) {
                throw new IllegalStateException("text dispatch was attempted (accepted="
                        + (accepted == null ? "unknown" : accepted.toString())
                        + "); possible input occurred; verification did not complete; do not retry automatically: "
                        + failure.getMessage(), failure);
            }
            throw failure;
        }
    }

    private String awaitExactReadback(AccessibilityNodeInfo target,
            QualificationProbe.ObservationSession session, ObservationBudget budget,
            Set<Integer> focusedIds, long deadline) throws ReflectiveOperationException {
        do {
            checkIdentity(session, budget, focusedIds);
            refreshTarget(target, budget);
            CharSequence text = target.getText();
            // Android may expose an empty editable field with null text.
            String actualText = text == null ? "" : text.toString();
            refreshTarget(target, budget);
            CharSequence confirmedText = target.getText();
            String confirmed = confirmedText == null ? "" : confirmedText.toString();
            checkIdentity(session, budget, focusedIds);
            if (SystemClock.elapsedRealtimeNanos() > deadline) {
                break;
            }
            if (request.text.equals(actualText) && request.text.equals(confirmed)) {
                return confirmed;
            }
            long remainingNanos = deadline - SystemClock.elapsedRealtimeNanos();
            if (remainingNanos <= 0) {
                break;
            }
            SystemClock.sleep(Math.min(READBACK_POLL_MILLIS, (remainingNanos + 999_999L) / 1_000_000L));
        } while (SystemClock.elapsedRealtimeNanos() <= deadline);
        throw new IllegalStateException("exact text readback did not match within two seconds");
    }

    private void checkIdentity(QualificationProbe.ObservationSession session, ObservationBudget budget,
            Set<Integer> expectedFocusedIds) throws ReflectiveOperationException {
        probe.checkDisplay();
        session.clearCache();
        Set<Integer> focusedIds = probe.focusedTargetWindows(session, budget);
        if (!expectedFocusedIds.equals(focusedIds) || !focusedIds.contains(request.windowId)) {
            throw new IllegalStateException("focused target window changed during text replacement");
        }
        probe.checkDisplay();
    }

    private void refreshTarget(AccessibilityNodeInfo target, ObservationBudget budget) {
        budget.requireLookup();
        if (!target.refresh()) {
            throw new IllegalStateException("target field disappeared during text replacement");
        }
        checkTarget(target);
    }

    private void checkTarget(AccessibilityNodeInfo target) {
        checkAllowedNode(target);
        if (!request.viewId.equals(target.getViewIdResourceName())) {
            throw new IllegalStateException("target field resource identity changed");
        }
        if (!target.isVisibleToUser() || !target.isEnabled() || !target.isEditable() || target.isPassword()) {
            throw new IllegalStateException("target field must be visible, enabled, editable and not a password");
        }
    }

    private void checkAllowedNode(AccessibilityNodeInfo node) {
        CharSequence packageName = node.getPackageName();
        SessionPolicy.checkPackage(request.probe.packageName, packageName == null ? null : packageName.toString());
        if (node.getWindowId() != request.windowId) {
            throw new IllegalStateException("target node does not belong to the expected window");
        }
    }

    private AccessibilityNodeInfo findUniqueTarget(QualificationProbe.ObservationSession session,
            ObservationBudget budget) {
        List<AccessibilityWindowInfo> windows = session.selectedWindows(request.probe.displayId);
        AccessibilityNodeInfo root = null;
        try {
            boolean foundWindow = false;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getDisplayId() != request.probe.displayId) {
                    throw new IllegalStateException("window does not belong to the selected display");
                }
                if (window.getId() != request.windowId) {
                    continue;
                }
                if (foundWindow || !window.isFocused()) {
                    throw new IllegalStateException("expected focused window is ambiguous or no longer focused");
                }
                foundWindow = true;
                budget.requireLookup();
                root = window.getRoot(0);
            }
            if (root == null) {
                throw new IllegalStateException("expected focused window has no accessible root");
            }
            checkAllowedNode(root);
            AccessibilityNodeInfo traversalRoot = root;
            root = null;
            return findUniqueNode(traversalRoot, budget);
        } finally {
            recycle(root);
            recycleWindows(windows);
        }
    }

    private AccessibilityNodeInfo findUniqueNode(AccessibilityNodeInfo root, ObservationBudget budget) {
        ArrayDeque<NodeFrame> stack = new ArrayDeque<NodeFrame>();
        AccessibilityNodeInfo match = null;
        boolean returned = false;
        try {
            stack.addLast(new NodeFrame(root));
            while (!stack.isEmpty()) {
                NodeFrame frame = stack.peekLast();
                if (!frame.checked) {
                    CharSequence packageName = frame.node.getPackageName();
                    if (packageName == null || !request.probe.packageName.contentEquals(packageName)) {
                        recycle(stack.removeLast().node);
                        continue;
                    }
                    checkAllowedNode(frame.node);
                    if (request.viewId.equals(frame.node.getViewIdResourceName())) {
                        if (match != null) {
                            throw new IllegalStateException("more than one node matches the requested view ID");
                        }
                        match = frame.node;
                    }
                    frame.childCount = frame.node.getChildCount();
                    frame.checked = true;
                }
                if (frame.nextChild >= frame.childCount) {
                    AccessibilityNodeInfo finished = stack.removeLast().node;
                    if (finished != match) {
                        recycle(finished);
                    }
                    continue;
                }
                budget.requireLookup();
                AccessibilityNodeInfo child = frame.node.getChild(frame.nextChild++, 0);
                if (child != null) {
                    stack.addLast(new NodeFrame(child));
                }
            }
            if (match == null) {
                throw new IllegalStateException("no node matches the requested view ID");
            }
            checkTarget(match);
            returned = true;
            return match;
        } finally {
            while (!stack.isEmpty()) {
                AccessibilityNodeInfo node = stack.removeLast().node;
                if (node != match) {
                    recycle(node);
                }
            }
            if (!returned) {
                recycle(match);
            }
        }
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

    private static final class NodeFrame {
        private final AccessibilityNodeInfo node;
        private int nextChild;
        private int childCount;
        private boolean checked;

        NodeFrame(AccessibilityNodeInfo node) {
            this.node = node;
        }
    }

    public static void main(String[] args) {
        final InputRequest request;
        try {
            // Parse first: invalid requests must not touch Android bootstrap.
            request = InputRequest.parse(args);
        } catch (Throwable failure) {
            QualificationCommand.reportFailure(failure);
            System.exit(1);
            return;
        }
        QualificationCommand.start(new QualificationCommand.Operation() {
            @Override
            public JSONObject run() throws Exception {
                return new QualificationInput(request).replaceText();
            }
        });
    }
}
