package agent.next.device;

/** Main-screen handoff primitives. Moving to the main screen transfers control to the user. */
public interface TaskController {

    TargetInfo moveToMain(TargetHandle target) throws DeviceException;

    TargetInfo moveToPrivate(TargetHandle target, DisplayHandle display) throws DeviceException;
}
