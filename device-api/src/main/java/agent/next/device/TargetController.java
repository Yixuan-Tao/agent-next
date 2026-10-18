package agent.next.device;

/** Target authorization/launch/state/release. */
public interface TargetController {

    /**
     * Authorize the task, launch the selected package on the private display,
     * and return its service-issued identity.
     */
    TargetHandle open(DisplayHandle display, TargetSpec spec) throws DeviceException;

    TargetInfo state(TargetHandle target) throws DeviceException;

    void release(TargetHandle target) throws DeviceException;
}
