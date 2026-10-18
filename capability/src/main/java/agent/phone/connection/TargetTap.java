package agent.phone.connection;

/** Receipt for one tap that was accepted against a specific observation and window. */
public final class TargetTap {
    public final int displayId, windowId, x, y;
    public final long atNanos;
    public final String observationId;
    public TargetTap(int displayId, int windowId, int x, int y, long atNanos, String observationId) {
        if (displayId < 0 || windowId < 0 || x < 0 || y < 0 || atNanos <= 0
                || observationId == null || observationId.isEmpty() || observationId.length() > 64) {
            throw new IllegalArgumentException("Malformed tap receipt");
        }
        this.displayId = displayId; this.windowId = windowId; this.x = x; this.y = y;
        this.atNanos = atNanos; this.observationId = observationId;
    }
    public void requireFor(DisplaySession display, String expectedObservationId) {
        if (display == null || display.status != DisplaySession.Status.ACTIVE
                || displayId != display.displayId || x >= display.width || y >= display.height
                || expectedObservationId == null || !expectedObservationId.equals(observationId)) {
            throw new IllegalArgumentException("Tap receipt does not match the active observation");
        }
    }
}
