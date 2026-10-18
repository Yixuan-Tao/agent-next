package agent.phone.connection;

/** Receipt for one swipe accepted against a specific observation and window. */
public final class TargetSwipe {
    public final int displayId, windowId, startX, startY, endX, endY, durationMs;
    public final long atNanos;
    public final String observationId;
    public TargetSwipe(int displayId,int windowId,int startX,int startY,int endX,int endY,int durationMs,
            long atNanos,String observationId) {
        if (displayId < 0 || windowId < 0 || startX < 0 || startY < 0 || endX < 0 || endY < 0
                || durationMs < 100 || durationMs > 2000 || atNanos <= 0
                || observationId == null || observationId.isEmpty() || observationId.length() > 64) {
            throw new IllegalArgumentException("Malformed swipe receipt");
        }
        this.displayId = displayId; this.windowId = windowId; this.startX = startX; this.startY = startY;
        this.endX = endX; this.endY = endY; this.durationMs = durationMs;
        this.atNanos = atNanos; this.observationId = observationId;
    }
    public void requireFor(DisplaySession display,String expectedObservationId) {
        if (display == null || display.status != DisplaySession.Status.ACTIVE
                || displayId != display.displayId || startX >= display.width || endX >= display.width
                || startY >= display.height || endY >= display.height
                || expectedObservationId == null || !expectedObservationId.equals(observationId)) {
            throw new IllegalArgumentException("Swipe receipt does not match the active observation");
        }
    }
}
