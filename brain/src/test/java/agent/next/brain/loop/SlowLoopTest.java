package agent.next.brain.loop;

import agent.next.brain.DefaultRiskGate;
import agent.next.brain.Fallback;
import agent.next.brain.InMemoryEpisodicMemory;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.RiskClass;
import agent.next.brain.SlowLoopReason;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;

import java.util.List;
import java.util.Set;

/** Slow-loop decision and replanning-runner tests. */
public final class SlowLoopTest {

    private SlowLoopTest() {
    }

    public static void run() {
        decisions();
        runnerReplans();
        runnerAsksUser();
        runnerAttemptLimit();
        runnerCrossAttemptBudget();
        System.out.println("SlowLoopTest: PASS (replace/insert/remove, ask, stop, continue, budget)");
    }

    private static void decisions() {
        TaskGraph replaceGraph = LoopTest.graph();
        SlowLoop slowReplace = new SlowLoop(context ->
                new SlowLoopDecision.Replace(replacement("find", "try another path"), "switch path"));
        SlowLoopResult replaced = slowReplace.run(context(replaceGraph));
        check(replaced.status() == SlowLoopResult.Status.REPLANNED, "replace replans");
        check("try another path".equals(replaceGraph.current().orElseThrow().intent()),
                "replacement is current");

        TaskGraph differentIdGraph = LoopTest.graph();
        SlowLoopResult differentId = new SlowLoop(context ->
                new SlowLoopDecision.Replace(replacement("other", "new id"), "bad id"))
                .run(context(differentIdGraph));
        check(differentId.status() == SlowLoopResult.Status.FAILED, "different id is rejected");

        TaskGraph insertGraph = LoopTest.graph();
        SlowLoopResult inserted = new SlowLoop(context ->
                new SlowLoopDecision.InsertBefore(replacement("clarify", "clarify first"),
                        "need clarification")).run(context(insertGraph));
        check(inserted.status() == SlowLoopResult.Status.REPLANNED, "insert before replans");
        check(insertGraph.total() == 2, "insert adds one node");
        check("clarify".equals(insertGraph.current().orElseThrow().id()),
                "inserted node is current");

        TaskGraph removeGraph = TaskGraph.of(
                replacement("find", "find it"),
                new Subgoal("after", "after it", Set.of("find"), List.of(), List.of(),
                        new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true));
        SlowLoopResult removed = new SlowLoop(context ->
                new SlowLoopDecision.RemoveCurrent("drop the blocked step")).run(context(removeGraph));
        check(removed.status() == SlowLoopResult.Status.REPLANNED, "remove current replans");
        check(removeGraph.total() == 1, "remove deletes one node");
        check("after".equals(removeGraph.current().orElseThrow().id()), "dependent is current");
        check(removeGraph.current().orElseThrow().dependsOn().isEmpty(),
                "dependent is rewired onto the removed node's dependencies");

        SlowLoopResult asked = new SlowLoop(context ->
                new SlowLoopDecision.AskUser("which result?")).run(context(LoopTest.graph()));
        check(asked.status() == SlowLoopResult.Status.NEED_USER, "ask user");

        SlowLoopResult stopped = new SlowLoop(context ->
                new SlowLoopDecision.Stop("stop here")).run(context(LoopTest.graph()));
        check(stopped.status() == SlowLoopResult.Status.STOPPED, "stop");

        SlowLoopResult continued = new SlowLoop(context ->
                new SlowLoopDecision.Continue("one more try")).run(context(LoopTest.graph()));
        check(continued.status() == SlowLoopResult.Status.CONTINUE, "continue");
    }

    private static void runnerReplans() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = LoopTest.graph();
        TaskSpec task = LoopTest.task(10);
        LoopPolicyFactory policies = (attempt, ignored) ->
                attempt == 1 ? new LoopTest.NoEffectPolicy() : new LoopTest.SuccessPolicy();
        SlowLoopPolicy slow = context ->
                new SlowLoopDecision.Replace(replacement("find", "try another path"), "switch path");
        LoopRunner runner = new LoopRunner(api, LoopTest.target(), task, graph, policies, slow,
                new DefaultRiskGate(), new LoopTest.FakeHumanGateway(),
                new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(), 3);
        LoopRunResult result = runner.run();
        check(result.result().status() == LoopResult.Status.DONE, "runner completes after replan");
        check(result.attempts() == 2, "runner uses two attempts");
        check(result.replans() == 1, "runner replans once");
        check(result.totalActions() == 4, "runner sums actions across attempts");
        check(graph.isDone(), "graph completes");
    }

    private static void runnerAsksUser() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = LoopTest.graph();
        LoopPolicyFactory policies = (attempt, ignored) -> new LoopTest.NoEffectPolicy();
        SlowLoopPolicy slow = context -> new SlowLoopDecision.AskUser("which path?");
        LoopRunner runner = new LoopRunner(api, LoopTest.target(), LoopTest.task(10), graph,
                policies, slow, new DefaultRiskGate(), new LoopTest.FakeHumanGateway(),
                new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(), 3);
        LoopRunResult result = runner.run();
        check(result.result().status() == LoopResult.Status.NEED_USER, "runner asks user");
        check(result.attempts() == 1, "no additional attempt after ask");
    }

    private static void runnerAttemptLimit() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = LoopTest.graph();
        LoopPolicyFactory policies = (attempt, ignored) -> new LoopTest.NoEffectPolicy();
        SlowLoopPolicy slow = context -> new SlowLoopDecision.Continue("again");
        LoopRunner runner = new LoopRunner(api, LoopTest.target(), LoopTest.task(10), graph,
                policies, slow, new DefaultRiskGate(), new LoopTest.FakeHumanGateway(),
                new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(), 2);
        LoopRunResult result = runner.run();
        check(result.result().status() == LoopResult.Status.FAILED, "attempt limit fails");
        check(result.attempts() == 2, "attempt limit honored");
    }

    private static void runnerCrossAttemptBudget() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = LoopTest.graph();
        LoopPolicyFactory policies = (attempt, ignored) ->
                attempt == 1 ? new LoopTest.NoEffectPolicy() : new LoopTest.SuccessPolicy();
        SlowLoopPolicy slow = context ->
                new SlowLoopDecision.Replace(replacement("find", "try another path"), "switch path");
        LoopRunner runner = new LoopRunner(api, LoopTest.target(), LoopTest.task(3), graph,
                policies, slow, new DefaultRiskGate(), new LoopTest.FakeHumanGateway(),
                new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(), 3);
        LoopRunResult result = runner.run();
        check(result.result().status() == LoopResult.Status.BUDGET_EXHAUSTED,
                "cross-attempt action budget stops the run");
        check(result.attempts() == 2, "budget test uses two attempts");
        check(result.replans() == 1, "budget test replans once");
        check(result.totalActions() == 3, "total actions respect the task budget");
    }

    private static SlowLoopContext context(TaskGraph graph) {
        WorkingMemory memory = new InMemoryWorkingMemory();
        LoopResult fast = LoopResult.slowLoop(SlowLoopReason.NO_EFFECT,
                "no effect", 2, null);
        return new SlowLoopContext(LoopTest.task(10), graph, graph.current(), fast, 1, memory);
    }

    private static Subgoal replacement(String id, String intent) {
        return new Subgoal(id, intent, Set.of(), List.of(), List.of(),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
