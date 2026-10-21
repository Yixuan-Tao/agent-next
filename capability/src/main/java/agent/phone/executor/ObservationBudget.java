package agent.phone.executor;

import java.util.function.LongSupplier;

/** One final focused-root lookup is reserved before any traversal begins. */
public final class ObservationBudget {
    public static final int MAX_LOOKUPS = 1000;
    public static final int MAX_NODES = 96;
    public static final int MAX_CONTENT_BYTES = 131072;
    public static final int MAX_PACKET_BYTES = 196608;
    private static final long TRAVERSAL_NANOS = 2_000_000_000L;
    private static final long FINAL_GUARD_RESERVE_NANOS = 1_000_000_000L;
    private final LongSupplier clock;
    private final long traversalDeadline;
    private int lookups, recorded, bytes = 4096;
    private boolean finalUsed;
    public boolean truncated;
    public ObservationBudget() {
        clock = null;
        traversalDeadline = Long.MAX_VALUE;
    }
    public ObservationBudget(LongSupplier clock,long requestDeadline) {
        this.clock = java.util.Objects.requireNonNull(clock,"clock");
        traversalDeadline = Math.min(clock.getAsLong() + TRAVERSAL_NANOS,
                requestDeadline - FINAL_GUARD_RESERVE_NANOS);
    }
    public boolean hasTraversalTime() {
        if (clock != null && clock.getAsLong() >= traversalDeadline) {
            truncated = true;
            return false;
        }
        return true;
    }
    public boolean lookup() {
        if (!hasTraversalTime()) return false;
        if (lookups >= MAX_LOOKUPS - 1) { truncated = true; return false; }
        lookups++;
        return true;
    }
    public void finalGuard() {
        if (finalUsed || lookups >= MAX_LOOKUPS) throw new IllegalStateException("Final guard budget exhausted");
        finalUsed = true;
        lookups++;
    }
    public boolean record(int estimatedBytes) {
        if (estimatedBytes < 0 || recorded >= MAX_NODES || estimatedBytes > MAX_CONTENT_BYTES - bytes) {
            truncated = true;
            return false;
        }
        bytes += estimatedBytes;
        recorded++;
        return true;
    }
    public int lookups() { return lookups; }
    public int recorded() { return recorded; }
    /** Keep actionable/semantic nodes; empty layout wrappers share their nearest retained parent. */
    public static boolean shouldRecord(boolean root, boolean visible, boolean clickable,
            boolean editable, boolean scrollable, boolean focused, boolean password,
            CharSequence text, CharSequence description) {
        return root || visible && (clickable || editable || scrollable || focused || password
                || text != null && text.length() != 0
                || description != null && description.length() != 0);
    }
    public static String limit(CharSequence value) {
        if (value == null) return "";
        String text = value.toString();
        return text.codePointCount(0, text.length()) <= 512 ? text : text.substring(0, text.offsetByCodePoints(0, 512));
    }
    public static boolean isLong(CharSequence value) {
        return value != null && Character.codePointCount(value, 0, value.length()) > 512;
    }
}
