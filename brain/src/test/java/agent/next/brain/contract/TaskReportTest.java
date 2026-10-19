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
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.session.SessionResult;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static agent.next.brain.TestSupport.check;

/** Task-level final verification tests. */
public final class TaskReportTest {

    private TaskReportTest() {
    }

    public static void run() {
        doneReport();
        missingOutcome();
        failedSession();
        System.out.println("TaskReportTest: PASS (done, partial, failed)");
    }

    private static void doneReport() {
        TaskContract contract = contract("result visible", "");
        TaskReport report = TaskReportBuilder.from(contract, plan("result visible"),
                SessionResult.done(2, 1));
        check(report.done(), "report done");
        check(report.outcomes().get(0).state() == TaskReport.OutcomeState.SATISFIED,
                "outcome satisfied");
    }

    private static void missingOutcome() {
        TaskContract contract = contract("different evidence", "");
        TaskReport report = TaskReportBuilder.from(contract, plan("result visible"),
                SessionResult.done(2, 1));
        check(!report.done(), "missing outcome is partial");
        check(report.status() == TaskReport.Status.PARTIAL, "partial status");
    }

    private static void failedSession() {
        TaskReport report = TaskReportBuilder.from(contract("result visible", ""),
                plan("result visible"), SessionResult.failed("boom", 1, 1, "s1", null));
        check(report.status() == TaskReport.Status.FAILED, "failed status");
        check(!report.done(), "failed is not done");
    }

    private static TaskContract contract(String description, String verifier) {
        return new TaskContract("report-task", "task", Map.of(),
                List.of(new TaskContract.RequiredOutcome("o1", description, verifier)),
                List.of(), Set.of("com.example.shop"), "com.example.shop",
                Set.of(), Set.of("visual"), AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Budget.defaults(), 1);
    }

    private static TaskPlan plan(String postcondition) {
        TaskSpec task = new TaskSpec("report-task", "task", List.of(),
                List.of(new SuccessCriterion("c1", postcondition, true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), Budget.defaults());
        Subgoal subgoal = new Subgoal("s1", "first", Set.of(), List.of(),
                List.of(new Condition("c1", postcondition, true)),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true,
                "com.example.shop", "visual");
        return new TaskPlan(task, List.of(subgoal), List.of());
    }
}
