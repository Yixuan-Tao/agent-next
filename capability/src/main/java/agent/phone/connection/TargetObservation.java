package agent.phone.connection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import agent.phone.executor.ObservationBudget;

/** Immutable evidence, never an instruction or a reusable authorization to act. */
public final class TargetObservation {
    public enum Status { READY, FOREGROUND_CONFLICT, TARGET_NOT_READY, FOREIGN_WINDOW, WINDOW_CHANGED }
    public final TargetSession target;
    public final DisplaySession display;
    public final String observationId;
    public final long sequence, startedNanos, endedNanos;
    public final int targetUid, focusedWindowId, lookupCount;
    public final Status status;
    public final Bounds windowBounds;
    public final boolean truncated;
    public final List<Node> nodes;

    public TargetObservation(TargetSession target, String id, long sequence, long started, long ended,
            Status status, int windowId, Bounds bounds, int lookups, boolean truncated, List<Node> nodes) {
        DisplaySession display = target == null ? null : target.display;
        if (display == null || display.status != DisplaySession.Status.ACTIVE || id == null || id.isEmpty()
                || id.length() > 64 || sequence <= 0 || started <= 0 || ended < started
                || status == null || nodes == null
                || lookups < 0 || lookups > ObservationBudget.MAX_LOOKUPS || nodes.size() > ObservationBudget.MAX_NODES
                || status == Status.READY && (windowId < 0 || bounds == null || bounds.left < 0 || bounds.top < 0
                || bounds.right > display.width || bounds.bottom > display.height || bounds.left >= bounds.right
                || bounds.top >= bounds.bottom)
                || status != Status.READY && (windowId != -1 || bounds != null || !nodes.isEmpty())) {
            throw new IllegalArgumentException("Malformed target observation");
        }
        int bytes = 4096;
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node == null || node.index != i || node.windowId != windowId || node.parent >= i
                    || !node.ref.equals(id + ":" + i)) throw new IllegalArgumentException("Invalid node identity");
            bytes += node.estimatedBytes();
        }
        if (bytes > ObservationBudget.MAX_CONTENT_BYTES) throw new IllegalArgumentException("Observation content exceeds budget");
        this.target = target; this.display = display; observationId = id; this.sequence = sequence;
        startedNanos = started; endedNanos = ended; targetUid = target.uid; this.status = status;
        focusedWindowId = windowId; windowBounds = bounds; lookupCount = lookups;
        this.truncated = truncated; this.nodes = Collections.unmodifiableList(new ArrayList<>(nodes));
    }
    public void requireFresh(TargetSession expected, long previousSequence) {
        if (expected == null || !target.sameIdentity(expected)
                || sequence <= previousSequence) throw new IllegalArgumentException("Stale observation identity or sequence");
    }
    /** Some Apps report inverted rectangles for hidden nodes; these have no actionable area. */
    public static Bounds nodeBounds(int left,int top,int right,int bottom,boolean visible) {
        if (!visible && (left > right || top > bottom)) return new Bounds(0,0,0,0);
        return new Bounds(left,top,right,bottom);
    }
    public static final class Bounds {
        public final int left, top, right, bottom;
        public Bounds(int left, int top, int right, int bottom) {
            if (left < -1000000 || top < -1000000 || right > 1000000 || bottom > 1000000
                    || left > right || top > bottom) throw new IllegalArgumentException("Invalid bounds");
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        @Override public boolean equals(Object value) {
            if (!(value instanceof Bounds)) return false;
            Bounds other = (Bounds) value;
            return left == other.left && top == other.top && right == other.right && bottom == other.bottom;
        }
        @Override public int hashCode() { return (((left * 31) + top) * 31 + right) * 31 + bottom; }
    }
    public static final class Node {
        public final String ref, text, description, resourceId, className;
        public final int index, windowId, parent;
        public final Bounds bounds;
        public final boolean clickable, editable, enabled, password, visible, focused, scrollable, textTruncated;
        public Node(String ref, int index, int windowId, int parent, Bounds bounds, String text,
                String description, String resourceId, String className, boolean clickable, boolean editable,
                boolean enabled, boolean password, boolean visible, boolean focused, boolean scrollable,
                boolean textTruncated) {
            if (ref == null || ref.length() > 80 || index < 0 || windowId < 0 || parent < -1 || parent >= index
                    || bounds == null || !limited(text) || !limited(description) || !limited(resourceId)
                    || !limited(className) || password && (!text.isEmpty() || !description.isEmpty())) {
                throw new IllegalArgumentException("Malformed observation node");
            }
            this.ref = ref; this.index = index; this.windowId = windowId; this.parent = parent;
            this.bounds = bounds; this.text = text; this.description = description;
            this.resourceId = resourceId; this.className = className; this.clickable = clickable;
            this.editable = editable; this.enabled = enabled; this.password = password; this.visible = visible;
            this.focused = focused; this.scrollable = scrollable; this.textTruncated = textTruncated;
        }
        public int estimatedBytes() {
            return 1024 + 2 * (ref.length() + text.length() + description.length() + resourceId.length() + className.length());
        }
        private static boolean limited(String value) {
            return value != null && value.codePointCount(0, value.length()) <= 512;
        }
    }
}
