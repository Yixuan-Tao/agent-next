package agent.next.brain.session;

import agent.next.device.DeviceException;
import agent.next.device.TargetHandle;

/** Opens/releases one app target at a time; implemented by the app/device adapter. */
public interface TargetBinder {

    TargetHandle open(String packageName) throws DeviceException;

    void release(TargetHandle target) throws DeviceException;
}
