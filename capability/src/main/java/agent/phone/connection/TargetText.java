package agent.phone.connection;

/** Receipt for one text insertion accepted against a specific observation and field. */
public final class TargetText {
    public final int displayId, windowId, textLength;
    public final String ref, observationId;
    public final long atNanos;
    public TargetText(int displayId, int windowId, String ref, int textLength, long atNanos, String observationId) {
        if (displayId < 0 || windowId < 0 || ref == null || ref.isEmpty() || ref.length() > 80
                || textLength <= 0 || textLength > 120 || atNanos <= 0
                || observationId == null || observationId.isEmpty() || observationId.length() > 64) {
            throw new IllegalArgumentException("Malformed text receipt");
        }
        this.displayId = displayId; this.windowId = windowId; this.ref = ref;
        this.textLength = textLength; this.atNanos = atNanos; this.observationId = observationId;
    }
    public void requireFor(DisplaySession display, String expectedObservationId, String expectedRef) {
        if (display == null || display.status != DisplaySession.Status.ACTIVE
                || displayId != display.displayId || expectedObservationId == null
                || !expectedObservationId.equals(observationId) || expectedRef == null
                || !expectedRef.equals(ref)) {
            throw new IllegalArgumentException("Text receipt does not match the active observation");
        }
    }
}
