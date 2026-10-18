package agent.next.app.calendar;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.DeviceException;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

/** Verifies the calendar row, not the calendar UI. */
public final class CalendarSubgoalVerifier implements SubgoalVerifier {

    private final CalendarStore store;

    public CalendarSubgoalVerifier(CalendarStore store) {
        this.store = store;
    }

    @Override
    public Verification verify(TaskSpec task, Subgoal subgoal, TargetHandle target,
            Observation observation) throws DeviceException {
        CalendarStore.EventSpec spec = CalendarStore.specFor(task.goal(), subgoal.intent());
        boolean exists = store.exists(spec);
        return new Verification(exists, exists ? "calendar event exists" : "calendar event missing");
    }
}
