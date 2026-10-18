package agent.next.device;

/**
 * Pure-JVM device port used by the brain.
 *
 * <p>Implementations own Android/Shizuku details. Callers own task policy,
 * observation freshness checks, and human confirmation; this port only
 * performs device operations and reports typed failures.</p>
 */
public interface DeviceApi extends AutoCloseable {

    /** Connect/authorize the underlying device service. Idempotent when already connected. */
    void connect() throws DeviceException;

    DeviceCapabilities capabilities() throws DeviceException;

    DisplayController displays();

    TargetController targets();

    ObservationController observations();

    InputController input();

    TaskController tasks();

    /** Idempotent. Later calls fail with UNAVAILABLE. */
    @Override
    void close();
}
