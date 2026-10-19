package agent.next.app.skill;

import android.content.Context;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.TargetHandle;
import agent.next.app.calendar.CalendarPolicy;
import agent.next.app.calendar.CalendarStore;
import agent.next.app.calendar.CalendarSubgoalVerifier;
import agent.phone.capability.BlockingDeviceApi;

import java.util.Optional;

/** Calendar contract adapter; no calendar UI automation. */
public final class CalendarDomainSkill implements DomainSkill {

    private static final String PACKAGE = "com.android.calendar";
    private static final String GOOGLE_PACKAGE = "com.google.android.calendar";
    private final CalendarStore store;
    private final CalendarSubgoalVerifier verifier;

    public CalendarDomainSkill(Context context) {
        this.store = new CalendarStore(context);
        this.verifier = new CalendarSubgoalVerifier(store);
    }

    @Override
    public boolean matches(String packageName, TaskSpec task) {
        return isCalendar(packageName);
    }

    @Override
    public void prepareTarget(String packageName, TargetHandle target, TaskSpec task,
            BlockingDeviceApi api) {
        // CalendarContract writes need no UI preparation.
    }

    @Override
    public Optional<LoopPolicy> policy(SkillContext context) {
        if (!isCalendar(context.target().packageName())) {
            return Optional.empty();
        }
        Subgoal subgoal = context.subgoal();
        return Optional.of(new RiskGuardPolicy(
                new CalendarPolicy(store, context.task(), subgoal), context.handoff()));
    }

    @Override
    public java.util.Set<String> verifierIds() {
        return java.util.Set.of("calendar.event");
    }

    @Override
    public SubgoalVerifier verifier(SubgoalVerifier fallback) {
        return (task, subgoal, target, observation) -> {
            if (isCalendar(target.packageName())) {
                return verifier.verify(task, subgoal, target, observation);
            }
            return fallback.verify(task, subgoal, target, observation);
        };
    }

    private static boolean isCalendar(String packageName) {
        return PACKAGE.equals(packageName) || GOOGLE_PACKAGE.equals(packageName);
    }
}
