package agent.next.brain.session;

import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.TargetHandle;

/** Trusted app bridge for capturing the current target frame. */
@FunctionalInterface
public interface TargetFrameSource {

    Frame capture(TargetHandle target) throws DeviceException;
}
