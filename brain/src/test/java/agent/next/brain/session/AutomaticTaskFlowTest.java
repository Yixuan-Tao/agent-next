package agent.next.brain.session;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.brain.loop.TaskExecutionControl;
import agent.next.brain.plan.InstalledApp;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.PlanningResult;
import agent.next.brain.plan.TaskPlan;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static agent.next.brain.TestSupport.check;

/** Ordinary submissions execute directly; clarification, cancellation and validation are real boundaries. */
public final class AutomaticTaskFlowTest {
    private AutomaticTaskFlowTest() { }

    public static void run() {
        try {
            ordinaryTaskRunsWithoutReview();
            clarificationAndReply();
            invalidEnvelopeDoesNotExecute();
            stopDuringPreparation();
            independentPauseOwnersAndStop();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        System.out.println("AutomaticTaskFlowTest: PASS (automatic start, clarification, validation, pause, cancel)");
    }

    private static void ordinaryTaskRunsWithoutReview() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        new AutomaticTaskFlow(request -> PlanningResult.plan(plan(request)))
                .run(request("查看商品"), LoopControl.none(), question -> {
                    throw new AssertionError("ordinary task must not request plan approval");
                }, (plan, contract) -> {
                    check(contract.goal().equals("查看商品"), "original task is internally validated");
                    executions.incrementAndGet();
                });
        check(executions.get() == 1, "validated preparation automatically enters execution once");
    }

    private static void clarificationAndReply() throws Exception {
        AtomicInteger questions = new AtomicInteger(), executions = new AtomicInteger();
        AutomaticTaskFlow flow = new AutomaticTaskFlow(request -> request.goal().contains("19点")
                ? PlanningResult.plan(plan(request)) : PlanningResult.clarify("日程几点开始？"));
        flow.run(request("创建日程"), LoopControl.none(), question -> questions.incrementAndGet(),
                (plan, contract) -> executions.incrementAndGet());
        check(questions.get() == 1 && executions.get() == 0, "missing necessary input is requested before execution");
        flow.run(request("创建日程\n用户补充信息：明天19点"), LoopControl.none(),
                question -> { throw new AssertionError("should use reply"); },
                (plan, contract) -> executions.incrementAndGet());
        check(executions.get() == 1, "reply continues directly without reviewing a plan");
    }

    private static void invalidEnvelopeDoesNotExecute() throws Exception {
        PlanningRequest request = request("查看商品");
        TaskPlan valid = plan(request);
        Subgoal wrong = new Subgoal("task", "wrong goal", Set.of(), List.of(),
                List.of(new Condition("other", "unrelated screen", true)),
                new Fallback(Fallback.Strategy.ASK_USER, "stop"), RiskClass.LOW, true,
                request.primaryPackage(), "visual");
        try {
            new AutomaticTaskFlow(candidate -> PlanningResult.plan(new TaskPlan(valid.task(), List.of(wrong), List.of())))
                    .run(request, LoopControl.none(), question -> { }, (plan, contract) -> {
                        throw new AssertionError("invalid preparation must not run");
                    });
            throw new AssertionError("invalid envelope must fail validation");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("required outcome"), "missing final outcome remains a hard validation boundary");
        }
    }

    private static void stopDuringPreparation() throws Exception {
        TaskExecutionControl control = new TaskExecutionControl();
        try {
            new AutomaticTaskFlow(request -> {
                control.stop();
                return PlanningResult.plan(plan(request));
            }).run(request("查看商品"), control, question -> { }, (plan, contract) -> {
                throw new AssertionError("cancelled model result must never start execution");
            });
            throw new AssertionError("stop must interrupt transition to execution");
        } catch (LoopStoppedException expected) {
            check(control.stopped(), "stop survives completion of preparation call");
        }
    }

    private static void independentPauseOwnersAndStop() throws Exception {
        TaskExecutionControl control = new TaskExecutionControl();
        control.pause("user");
        control.pause("preview");
        control.resume("preview");
        check(control.paused() && control.pausedBy("user"), "closing preview preserves an explicit user pause");
        CountDownLatch entered = new CountDownLatch(1), stopped = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            entered.countDown();
            try { control.checkpoint(); }
            catch (LoopStoppedException expected) { stopped.countDown(); }
        });
        worker.start();
        check(entered.await(1, TimeUnit.SECONDS), "worker entered checkpoint");
        check(!stopped.await(30, TimeUnit.MILLISECONDS), "paused task stays paused");
        control.stop();
        check(stopped.await(1, TimeUnit.SECONDS), "stop wakes paused execution without a resume step");
        worker.join(1000);
        TaskExecutionControl resumed = new TaskExecutionControl();
        resumed.pause("user");
        resumed.resume("user");
        resumed.checkpoint();
        check(!resumed.paused(), "explicit continue releases user pause");
    }

    private static PlanningRequest request(String goal) {
        return new PlanningRequest("direct-task", goal, List.of(), Set.of("com.example.shop"),
                List.of(new InstalledApp("com.example.shop", "Shop")), "com.example.shop",
                Set.of(), Set.of("visual"), AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE));
    }

    private static TaskPlan plan(PlanningRequest request) {
        TaskSpec task = new TaskSpec(request.taskId(), request.goal(), request.constraints(),
                List.of(new SuccessCriterion("outcome", "final result visible", true)), request.autonomy(),
                request.allowedApps(), request.deniedActions(), Budget.defaults());
        Subgoal outcome = new Subgoal("task", request.goal(), Set.of(), List.of(),
                List.of(new Condition("result", "final result visible", true)),
                new Fallback(Fallback.Strategy.RETRY, "observe"), RiskClass.LOW, true,
                request.primaryPackage(), "visual");
        return new TaskPlan(task, List.of(outcome), List.of());
    }
}
