package agent.next.app.skill;

import android.content.Context;

import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.TargetHandle;
import agent.next.app.calendar.CalendarPolicy;
import agent.next.app.calendar.CalendarStore;
import agent.next.app.calendar.CalendarSubgoalVerifier;
import agent.phone.capability.BlockingDeviceApi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    public TaskPlan fixedPlan(TaskPlan planned) {
        if (planned == null || planned.subgoals().isEmpty()) {
            return null;
        }
        String goal = planned.task().goal();
        boolean creates = goal.contains("添加") || goal.contains("新建")
                || goal.contains("创建") || goal.contains("设置");
        boolean calendar = goal.contains("提醒") || goal.contains("日程")
                || goal.contains("日历");
        String pkg = planned.subgoals().get(0).targetPackage();
        if (!creates || !calendar || !isCalendar(pkg)) {
            return null;
        }
        Subgoal subgoal = new Subgoal("s1", "创建日历事件并添加提醒", Set.of(),
                List.of(new Condition("s1.pre", "calendar domain ready", true)),
                List.of(new Condition("s1.post", "日历事件存在", true)),
                new Fallback(Fallback.Strategy.RETRY, "retry once"),
                RiskClass.REVERSIBLE_SIDE_EFFECT, true, pkg, "calendar.event");
        return new TaskPlan(planned.task(), List.of(subgoal),
                List.of("calendar event path is deterministic"));
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
