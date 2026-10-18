package agent.phone.executor;

/** One final focused-root lookup is reserved before any traversal begins. */
public final class ObservationBudget {
    public static final int MAX_LOOKUPS = 1000;
    public static final int MAX_NODES = 96;
    public static final int MAX_CONTENT_BYTES = 131072;
    public static final int MAX_PACKET_BYTES = 196608;
    private int lookups, recorded, bytes = 4096;
    private boolean finalUsed;
    public boolean truncated;
    public boolean lookup() {
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
    public static String limit(CharSequence value) {
        if (value == null) return "";
        String text = value.toString();
        return text.codePointCount(0, text.length()) <= 512 ? text : text.substring(0, text.offsetByCodePoints(0, 512));
    }
    public static boolean isLong(CharSequence value) {
        return value != null && Character.codePointCount(value, 0, value.length()) > 512;
    }
}
