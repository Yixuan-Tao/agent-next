package agent.next.brain.loop;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.SlowLoopReason;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.contract.TaskContract;
import agent.next.brain.plan.PlanningResult;
import agent.next.brain.plan.TaskPlan;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static agent.next.brain.TestSupport.check;

/** M3 bounded replan uses PlanValidator and stops at the budget. */
public final class BoundedReplanTest {

    private BoundedReplanTest() {
    }

    public static void run() {
        replacement();
        bounded();
        clarification();
        invalidPlan();
        System.out.println("BoundedReplanTest: PASS (replace, budget, clarify, invalid)");
    }

    private static void replacement() {
        BoundedReplanPolicy policy = policy(context -> PlanningResult.plan(plan("result visible")));
        SlowLoopDecision decision = policy.decide(context());
        check(decision instanceof SlowLoopDecision.Replace, "replan replaces current");
        check(((SlowLoopDecision.Replace) decision).replacement().id().equals("s1"),
                "replacement keeps id");
    }

    private static void bounded() {
        BoundedReplanPolicy policy = policy(context -> PlanningResult.plan(plan("result visible")));
        policy.decide(context());
        SlowLoopDecision second = policy.decide(context());
        check(second instanceof SlowLoopDecision.Stop, "replan budget stops");
    }

    private static void clarification() {
        BoundedReplanPolicy policy = policy(context -> PlanningResult.clarify("哪个城市？"));
        SlowLoopDecision decision = policy.decide(context());
        check(decision instanceof SlowLoopDecision.AskUser, "clarification asks user");
    }

    private static void invalidPlan() {
        BoundedReplanPolicy policy = policy(context -> PlanningResult.plan(plan("no evidence")));
        SlowLoopDecision decision = policy.decide(context());
        check(decision instanceof SlowLoopDecision.Stop, "invalid replan stops");
    }

    private static BoundedReplanPolicy policy(ReplanSource source) {
        return new BoundedReplanPolicy(contract(), Set.of("visual"), 1, source);
    }

    private static SlowLoopContext context() {
        Subgoal current = subgoal("s1", "old approach", "result visible");
        TaskGraph graph = TaskGraph.of(current);
        LoopResult fast = LoopResult.slowLoop(SlowLoopReason.NO_EFFECT, "no effect", 2, null);
        return new SlowLoopContext(task(), graph, Optional.of(current), fast, 2,
                new agent.next.brain.InMemoryWorkingMemory());
    }

    private static TaskContract contract() {
        return new TaskContract("replan-task", "task", Map.of(),
                List.of(new TaskContract.RequiredOutcome("o1", "result visible", "")),
                List.of(), Set.of("com.example.shop"), "com.example.shop",
                Set.of(), Set.of("visual"), AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Budget.defaults(), 1);
    }

    private static TaskPlan plan(String postcondition) {
        TaskSpec task = task();
        Subgoal s1 = subgoal("s1", "new approach", postcondition);
        return new TaskPlan(task, List.of(s1), List.of());
    }

    private static TaskSpec task() {
        return new TaskSpec("replan-task", "task", List.of(),
                List.of(new SuccessCriterion("c1", "result visible", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), Budget.defaults());
    }

    private static Subgoal subgoal(String id, String intent, String postcondition) {
        return new Subgoal(id, intent, Set.of(), List.of(),
                List.of(new Condition(id + ".post", postcondition, true)),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true,
                "com.example.shop", "visual");
    }
}
