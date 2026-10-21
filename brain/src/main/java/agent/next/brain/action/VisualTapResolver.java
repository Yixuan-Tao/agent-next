package agent.next.brain.action;

import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.TargetHandle;

/** Resolves a semantic target against the supplied current screenshot. */
public interface VisualTapResolver {
    VisualTapResolution locate(TargetHandle target, String description, Frame frame) throws DeviceException;
}
