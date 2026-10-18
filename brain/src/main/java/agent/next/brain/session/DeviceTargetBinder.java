package agent.next.brain.session;

import agent.next.brain.TaskSpec;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.DisplayHandle;
import agent.next.device.TargetHandle;
import agent.next.device.TargetSpec;

import java.util.Objects;

/** Binds the next subgoal's app to the already-created private display. */
public final class DeviceTargetBinder implements TargetBinder {

    private final DeviceApi api;
    private final DisplayHandle display;
    private final TaskSpec task;

    public DeviceTargetBinder(DeviceApi api, DisplayHandle display, TaskSpec task) {
        this.api = Objects.requireNonNull(api, "api");
        this.display = Objects.requireNonNull(display, "display");
        this.task = Objects.requireNonNull(task, "task");
    }

    @Override
    public TargetHandle open(String packageName) throws DeviceException {
        return api.targets().open(display,
                new TargetSpec(task.id(), packageName, task.allowedApps()));
    }

    @Override
    public void release(TargetHandle target) throws DeviceException {
        api.targets().release(target);
    }
}
