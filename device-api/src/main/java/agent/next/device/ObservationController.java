package agent.next.device;

/** Read-only observation and single-frame capture. */
public interface ObservationController {

    Observation observe(TargetHandle target, ObservationOptions options) throws DeviceException;

    Frame captureFrame(TargetHandle target) throws DeviceException;
}
