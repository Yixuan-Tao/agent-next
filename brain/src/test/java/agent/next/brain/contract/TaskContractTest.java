package agent.next.brain.contract;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskSpec;
import agent.next.brain.plan.InstalledApp;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.TaskPlan;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static agent.next.brain.TestSupport.check;

/** M2 contract and plan validation host tests. */
public final class TaskContractTest {

    private TaskContractTest() {
    }

    public static void run() {
        validPlan();
        missingOutcome();
        unregisteredVerifier();
        disallowedTarget();
        primaryMismatch();
        factoryCoverage();
        System.out.println("TaskContractTest: PASS (contract, coverage, verifier, app scope)");
    }

    private static void validPlan() {
        PlanValidator.Result result = validator().validate(contract("visual"),
                plan("com.example.shop", "visual", "com.example.shop"), Set.of("visual"));
        check(result.valid(), "valid plan: " + result.errors());
    }

    private static void missingOutcome() {
        PlanValidator.Result result = validator().validate(contract("calendar.event"),
                plan("com.example.shop", "visual", "com.example.shop"), Set.of("visual"));
        check(!result.valid(), "missing required verifier is invalid");
        check(result.errors().stream().anyMatch(error -> error.contains("outcome")),
                "missing outcome error");
    }

    private static void unregisteredVerifier() {
        PlanValidator.Result result = validator().validate(contract("visual"),
                plan("com.example.shop", "visual", "com.example.shop"), Set.of());
        check(!result.valid(), "unregistered verifier is invalid");
    }

    private static void disallowedTarget() {
        PlanValidator.Result result = validator().validate(contract("visual"),
                plan("com.example.other", "visual", "com.example.shop"), Set.of("visual"));
        check(!result.valid(), "target outside contract is invalid");
    }

    private static void primaryMismatch() {
        PlanValidator.Result result = validator().validate(contract("visual"),
                plan("com.example.other", "visual", "com.example.shop"), Set.of("visual"));
        check(!result.valid(), "first target must be primary");
    }

    private static void factoryCoverage() {
        TaskSpec task = new TaskSpec("m2-task", "test plan", List.of(),
                List.of(new SuccessCriterion("c1", "result visible", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), Budget.defaults());
        Subgoal subgoal = new Subgoal("s1", "first", Set.of(), List.of(),
                List.of(new Condition("c1", "result visible", true)),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true,
                "com.example.shop", "visual");
        TaskPlan plan = new TaskPlan(task, List.of(subgoal), List.of());
        PlanningRequest request = new PlanningRequest("m2-task", "test plan", List.of(),
                Set.of("com.example.shop"), List.of(new InstalledApp("com.example.shop", "Shop")),
                "com.example.shop", Set.of("payment"), Set.of("visual"),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT));
        TaskContract contract = TaskContractFactory.from(request, plan);
        check(contract.requiredOutcomes().size() == 1, "factory outcome count");
        check(new PlanValidator().validate(contract, plan, Set.of("visual")).valid(),
                "factory contract validates coverage");
    }

    private static PlanValidator validator() {
        return new PlanValidator();
    }

    private static TaskContract contract(String outcomeVerifier) {
        return new TaskContract("m2-task", "test plan", Map.of("city", "上海"),
                List.of(new TaskContract.RequiredOutcome("o1", "result visible", outcomeVerifier)),
                List.of("no payment"), Set.of("com.example.shop", "com.example.other"),
                "com.example.shop", Set.of("payment", "send", "delete"),
                Set.of("visual", "calendar.event"), AutonomyGrant.of(
                        AutonomyLevel.A3_CONFIRM_COMMIT), Budget.defaults(), 1);
    }

    private static TaskPlan plan(String first, String verifier, String second) {
        TaskSpec task = new TaskSpec("m2-task", "test plan", List.of(),
                List.of(new SuccessCriterion("c1", "result visible", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop", "com.example.other"), Set.of(), Budget.defaults());
        Subgoal s1 = new Subgoal("s1", "first", Set.of(), List.of(), List.of(),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true,
                first, verifier);
        Subgoal s2 = new Subgoal("s2", "second", Set.of("s1"), List.of(), List.of(),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true,
                second, verifier);
        return new TaskPlan(task, List.of(s1, s2), List.of());
    }
}
