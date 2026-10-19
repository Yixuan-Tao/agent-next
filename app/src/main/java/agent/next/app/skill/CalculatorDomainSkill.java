package agent.next.app.skill;

import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.TargetHandle;
import agent.next.app.skill.VisualButtonLocator;
import agent.next.brain.model.ChatCompletionClient;
import agent.phone.capability.BlockingDeviceApi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Visual calculator adapter: screenshot -> semantic button -> point tap. */
public final class CalculatorDomainSkill implements DomainSkill {

    private static final String PACKAGE = "com.miui.calculator";
    private final VisualButtonLocator locator;
    private final CalculatorSubgoalVerifier verifier = new CalculatorSubgoalVerifier();

    public CalculatorDomainSkill(ChatCompletionClient client) {
        this.locator = new VisualButtonLocator(client);
    }

    @Override
    public boolean matches(String packageName, TaskSpec task) {
        return PACKAGE.equals(packageName);
    }

    @Override
    public void prepareTarget(String packageName, TargetHandle target, TaskSpec task,
            BlockingDeviceApi api) {
        // The calculator opens on its keypad; no extra preparation.
    }

    @Override
    public Optional<LoopPolicy> policy(SkillContext context) {
        if (!PACKAGE.equals(context.target().packageName())) {
            return Optional.empty();
        }
        if (CalculatorExpression.parse(context.task().goal(), context.subgoal().intent())
                .isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RiskGuardPolicy(
                new CalculatorPolicy(locator, context.task(), context.subgoal()),
                context.handoff()));
    }

    @Override
    public TaskPlan fixedPlan(TaskPlan planned) {
        if (planned == null || planned.subgoals().isEmpty()) {
            return null;
        }
        String goal = planned.task().goal();
        String pkg = planned.subgoals().get(0).targetPackage();
        if (!goal.contains("计算") || !PACKAGE.equals(pkg)
                || CalculatorExpression.parse(goal, "").isEmpty()) {
            return null;
        }
        Subgoal subgoal = new Subgoal("s1", "在计算器上输入表达式并计算结果", Set.of(),
                List.of(new Condition("s1.pre", "calculator ready", true)),
                List.of(new Condition("s1.post", "计算结果在显示区可见", true)),
                new Fallback(Fallback.Strategy.RETRY, "retry once"),
                RiskClass.LOW, true, pkg, "calculator.result");
        return new TaskPlan(planned.task(), List.of(subgoal),
                List.of("calculator visual tap path is deterministic"));
    }

    @Override
    public Set<String> verifierIds() {
        return Set.of("calculator.result");
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
