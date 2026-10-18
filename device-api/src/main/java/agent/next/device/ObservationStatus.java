package agent.next.device;

/** Why an observation succeeded or was rejected. */
public enum ObservationStatus {
    READY,
    FOREGROUND_CONFLICT,
    TARGET_NOT_READY,
    FOREIGN_WINDOW,
    WINDOW_CHANGED
}
