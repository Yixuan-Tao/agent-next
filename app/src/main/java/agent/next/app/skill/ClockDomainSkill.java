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
import agent.next.app.clock.ClockPolicy;
import agent.next.app.clock.ClockStore;
import agent.next.app.clock.ClockSubgoalVerifier;
import agent.phone.capability.BlockingDeviceApi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Clock alarm adapter through AlarmClock; no time-picker UI automation. */
public final class ClockDomainSkill implements DomainSkill {

    private static final String PACKAGE = "com.android.deskclock";
    private final ClockStore store;
    private final ClockSubgoalVerifier verifier;

    public ClockDomainSkill(Context context) {
        this.store = new ClockStore(context);
        this.verifier = new ClockSubgoalVerifier(store);
    }

    @Override
    public boolean matches(String packageName, TaskSpec task) {
        return PACKAGE.equals(packageName);
    }

    @Override
    public void prepareTarget(String packageName, TargetHandle target, TaskSpec task,
            BlockingDeviceApi api) {
        // AlarmClock writes need no Clock UI preparation.
    }

    @Override
    public Optional<LoopPolicy> policy(SkillContext context) {
        if (!PACKAGE.equals(context.target().packageName())) {
            return Optional.empty();
        }
        return Optional.of(new RiskGuardPolicy(
                new ClockPolicy(store, context.task(), context.subgoal()), context.handoff()));
    }

    @Override
    public TaskPlan fixedPlan(TaskPlan planned) {
        if (planned == null || planned.subgoals().isEmpty()) {
            return null;
        }
        String goal = planned.task().goal();
        String pkg = planned.subgoals().get(0).targetPackage();
        if (!goal.contains("闹钟") || !PACKAGE.equals(pkg)) {
            return null;
        }
        Subgoal subgoal = new Subgoal("s1", "设置一个闹钟", Set.of(),
                List.of(new Condition("s1.pre", "clock domain ready", true)),
                List.of(new Condition("s1.post", "系统下一个闹钟与目标时间一致", true)),
                new Fallback(Fallback.Strategy.RETRY, "retry once"),
                RiskClass.REVERSIBLE_SIDE_EFFECT, true, pkg, "clock.alarm");
        return new TaskPlan(planned.task(), List.of(subgoal),
                List.of("alarm path is deterministic"));
    }

    @Override
    public Set<String> verifierIds() {
        return Set.of("clock.alarm");
    }

    @Override
    public SubgoalVerifier verifier(SubgoalVerifier fallback) {
        return (task, subgoal, target, observation) -> {
            if (PACKAGE.equals(target.packageName())) {
                return verifier.verify(task, subgoal, target, observation);
            }
            return fallback.verify(task, subgoal, target, observation);
        };
    }
}
