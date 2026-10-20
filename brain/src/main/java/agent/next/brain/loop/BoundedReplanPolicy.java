package agent.next.brain.loop;

import agent.next.brain.Subgoal;
import agent.next.brain.contract.PlanValidator;
import agent.next.brain.contract.TaskContract;
import agent.next.brain.plan.PlanningResult;
import agent.next.brain.plan.TaskPlan;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One bounded replan: ask for a candidate plan, validate it with the same
 * PlanValidator as initial planning, then replace only the current subgoal.
 */
public final class BoundedReplanPolicy implements SlowLoopPolicy {

    private final TaskContract contract;
    private final Set<String> registeredVerifiers;
    private final int maxReplans;
    private final ReplanSource source;
    private int replans;

    public BoundedReplanPolicy(TaskContract contract, Set<String> registeredVerifiers,
            int maxReplans, ReplanSource source) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.registeredVerifiers = Set.copyOf(registeredVerifiers);
        if (maxReplans < 0) {
            throw new IllegalArgumentException("maxReplans must not be negative");
        }
        this.maxReplans = maxReplans;
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public SlowLoopDecision decide(SlowLoopContext context) {
        if (replans >= maxReplans) {
            return new SlowLoopDecision.Stop("replan budget exhausted");
        }
        Optional<Subgoal> current = context.current();
        if (current.isEmpty()) {
            return new SlowLoopDecision.Stop("no current subgoal to replan");
        }
        PlanningResult result;
        try {
            result = source.replan(context);
        } catch (Exception failure) {
            return new SlowLoopDecision.Stop("replan failed: " + failure.getMessage());
        }
        if (result == null) {
            return new SlowLoopDecision.Stop("replan returned no result");
        }
        if (result.needsClarification()) {
            return new SlowLoopDecision.AskUser(result.clarification());
        }
        TaskPlan plan = result.plan();
        PlanValidator.Result validation = new PlanValidator().validate(
                contract, plan, registeredVerifiers);
        if (!validation.valid()) {
            return new SlowLoopDecision.Stop("invalid replan: " + validation.errors());
        }
        Subgoal first = plan.subgoals().get(0);
        Subgoal replacement = new Subgoal(current.get().id(), first.intent(),
                current.get().dependsOn(), first.preconditions(), first.postconditions(),
                first.fallback(), first.risk(), first.required(),
                first.targetPackage(), first.verifierId());
        replans++;
        return new SlowLoopDecision.Replace(replacement, "bounded replan");
    }
}
