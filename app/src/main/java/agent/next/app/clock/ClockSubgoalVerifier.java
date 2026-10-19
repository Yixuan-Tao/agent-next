package agent.next.app.clock;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.DeviceException;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

/** Verifies the next system alarm, not the Clock UI. */
public final class ClockSubgoalVerifier implements SubgoalVerifier {

    private final ClockStore store;

    public ClockSubgoalVerifier(ClockStore store) {
        this.store = store;
    }

    @Override
    public Verification verify(TaskSpec task, Subgoal subgoal, TargetHandle target,
            Observation observation) throws DeviceException {
        ClockStore.AlarmSpec spec = ClockStore.specFor(task.goal(), subgoal.intent());
        boolean exists = store.exists(spec);
        return new Verification(exists, exists ? "next alarm matches" : "next alarm missing");
    }
}
