package agent.next.device;

/** Directed input bound to a current observation. Implementations must reject stale evidence. */
public interface InputController {

    InputReceipt tap(TargetHandle target, Observation observation, Point point) throws DeviceException;

    InputReceipt setText(TargetHandle target, Observation observation, String nodeRef, String text)
            throws DeviceException;

    InputReceipt swipe(TargetHandle target, Observation observation, Point from, Point to,
            int durationMs) throws DeviceException;

    InputReceipt back(TargetHandle target, Observation observation) throws DeviceException;
}
