package agent.next.device;

/** Private-display lifecycle. Every returned identity must be checked before reuse. */
public interface DisplayController {

    DisplayHandle create(DisplaySpec spec) throws DeviceException;

    DisplayInfo check(DisplayHandle display) throws DeviceException;

    void release(DisplayHandle display) throws DeviceException;
}
