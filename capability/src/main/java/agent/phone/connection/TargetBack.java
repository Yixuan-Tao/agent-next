package agent.phone.connection;

/** Receipt for one back key accepted against a specific observation and window. */
public final class TargetBack {
    public final int displayId, windowId;
    public final long atNanos;
    public final String observationId;
    public TargetBack(int displayId,int windowId,long atNanos,String observationId) {
        if (displayId < 0 || windowId < 0 || atNanos <= 0
                || observationId == null || observationId.isEmpty() || observationId.length() > 64) {
            throw new IllegalArgumentException("Malformed back receipt");
        }
        this.displayId = displayId; this.windowId = windowId; this.atNanos = atNanos;
        this.observationId = observationId;
    }
    public void requireFor(DisplaySession display,String expectedObservationId) {
        if (display == null || display.status != DisplaySession.Status.ACTIVE
                || displayId != display.displayId
                || expectedObservationId == null || !expectedObservationId.equals(observationId)) {
            throw new IllegalArgumentException("Back receipt does not match the active observation");
        }
    }
}
