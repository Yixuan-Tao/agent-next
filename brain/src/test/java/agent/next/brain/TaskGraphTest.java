package agent.next.brain;

import java.util.List;
import java.util.Set;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class TaskGraphTest {

    private TaskGraphTest() {
    }

    static void run() {
        constructionAndOrder();
        dependencyEnforcement();
        rejectsInvalidGraphs();
        replanningMutations();
        dynamicInsertAndRemove();
        scopedSubgoals();
        optionalAndFailure();
        System.out.println("TaskGraphTest: PASS (dependencies, completion, replanning, failure)");
    }

    private static void constructionAndOrder() {
        TaskGraph graph = new TaskGraph(List.of(
                goal("a", true),
                goal("b", true, "a"),
                goal("c", true)));

        check(graph.total() == 3, "total nodes");
        check(graph.ready().stream().map(Subgoal::id).toList().equals(List.of("a", "c")), "ready order");
        check(graph.current().orElseThrow().id().equals("a"), "first ready is current");
        check(graph.progress() == 0.0, "no progress yet");
        check(!graph.blocked(), "graph is runnable");

        graph.activate("a");
        check(graph.current().orElseThrow().id().equals("a"), "active node stays current");
        expect(IllegalStateException.class, () -> graph.activate("c"), "only one active node");
        graph.complete("a");
        check(graph.current().orElseThrow().id().equals("b"), "dependency unlocks b before c");
        check(graph.progress() == 1.0 / 3.0, "progress after one completion");

        graph.activate("b");
        graph.complete("b");
        graph.activate("c");
        graph.complete("c");
        check(graph.isDone(), "all nodes completed");
        check(graph.progress() == 1.0, "full progress");
        check(graph.snapshot().get("a") == TaskGraph.NodeStatus.COMPLETED, "snapshot status");
    }

    private static void dependencyEnforcement() {
        TaskGraph graph = new TaskGraph(List.of(goal("a", true), goal("b", true, "a")));

        expect(IllegalStateException.class, () -> graph.activate("b"), "cannot activate before dependency");
        expect(IllegalStateException.class, () -> graph.complete("b"), "cannot complete before dependency");

        graph.activate("a");
        graph.complete("a");
        graph.activate("b");
        graph.complete("b");
        graph.complete("b");
        check(graph.isDone(), "idempotent complete and done");
    }

    private static void rejectsInvalidGraphs() {
        expect(IllegalArgumentException.class, () -> new TaskGraph(List.of()), "empty graph");
        expect(IllegalArgumentException.class,
                () -> new TaskGraph(List.of(goal("a", true), goal("a", true))), "duplicate id");
        expect(IllegalArgumentException.class,
                () -> new TaskGraph(List.of(goal("a", true, "missing"))), "missing dependency");
        expect(IllegalArgumentException.class,
                () -> new TaskGraph(List.of(goal("a", true, "a"))), "self dependency");
        expect(IllegalArgumentException.class,
                () -> new TaskGraph(List.of(goal("a", true, "b"), goal("b", true, "a"))), "cycle");

        TaskGraph graph = new TaskGraph(List.of(goal("a", true)));
        expect(IllegalArgumentException.class, () -> graph.add(goal("b", true, "missing")), "add with missing dependency");
        check(graph.total() == 1, "failed add rolls back");
        graph.add(goal("b", true, "a"));
        check(graph.total() == 2, "valid add");

        TaskGraph cyclic = new TaskGraph(List.of(goal("a", true), goal("b", true, "a")));
        expect(IllegalArgumentException.class,
                () -> cyclic.replace(goal("a", true, "b")), "replace cannot introduce cycle");
        check(cyclic.ready().stream().map(Subgoal::id).toList().equals(List.of("a")), "failed replace rolls back");
    }

    private static void replanningMutations() {
        TaskGraph graph = new TaskGraph(List.of(goal("a", true)));
        graph.add(goal("b", true, "a"));
        graph.activate("a");
        graph.complete("a");
        check(graph.ready().stream().map(Subgoal::id).toList().equals(List.of("b")), "b is ready");

        graph.replace(new Subgoal("b", "new intent", Set.of("a"), List.of(), List.of(),
                new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, true));
        check(graph.ready().get(0).intent().equals("new intent"), "replace updates intent");
        expect(IllegalStateException.class, () -> graph.replace(goal("a", true)), "cannot replace completed node");
        expect(IllegalStateException.class, () -> graph.remove("a"), "cannot remove completed node");

        graph.remove("b");
        check(graph.total() == 1, "remove pending leaf");
        expect(IllegalArgumentException.class, () -> graph.remove("missing"), "remove unknown node");

        TaskGraph dependent = new TaskGraph(List.of(goal("a", true), goal("b", true, "a")));
        expect(IllegalStateException.class, () -> dependent.remove("a"), "cannot remove dependency");

        TaskGraph failed = new TaskGraph(List.of(goal("a", true)));
        failed.activate("a");
        failed.fail("a");
        failed.replace(new Subgoal("a", "new approach", Set.of(), List.of(), List.of(),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true));
        check(failed.status("a") == TaskGraph.NodeStatus.PENDING, "replacing a failed node retries it");
        check(failed.ready().stream().anyMatch(subgoal -> subgoal.id().equals("a")), "replaced node is ready");
    }

    private static void optionalAndFailure() {
        TaskGraph graph = new TaskGraph(List.of(goal("opt", false), goal("req", true, "opt")));
        graph.skip("opt");
        check(graph.ready().stream().map(Subgoal::id).toList().equals(List.of("req")), "skipped optional unlocks dependent");

        graph.activate("req");
        graph.fail("req");
        check(graph.hasFailed(), "failure recorded");
        check(!graph.isDone(), "failed graph is not done");
        check(graph.blocked(), "failed graph is blocked");
        expect(IllegalArgumentException.class, () -> graph.skip("req"), "required cannot be skipped");

        graph.reset("req");
        check(graph.ready().stream().map(Subgoal::id).toList().equals(List.of("req")), "reset makes node ready again");
        graph.activate("req");
        graph.complete("req");
        check(graph.isDone(), "optional skip plus required completion is done");
    }

    private static void dynamicInsertAndRemove() {
        TaskGraph graph = new TaskGraph(List.of(
                goal("a", true), goal("b", true, "a"), goal("c", true, "b")));
        graph.insertBefore("b", new Subgoal("repair", "repair the page", Set.of(),
                List.of(), List.of(), new Fallback(Fallback.Strategy.RETRY, "retry"),
                RiskClass.LOW, true));
        check(graph.total() == 4, "insert adds a node");
        check(graph.status("b") == TaskGraph.NodeStatus.PENDING, "current node restarts pending");
        check(graph.current().orElseThrow().id().equals("a"), "first dependency is current");
        graph.activate("a");
        graph.complete("a");
        check(graph.current().orElseThrow().id().equals("repair"), "repair runs before current");
        graph.activate("repair");
        graph.complete("repair");
        check(graph.current().orElseThrow().id().equals("b"), "original current resumes");
        graph.activate("b");
        expect(IllegalStateException.class, () -> graph.insertBefore("b", goal("late", true)),
                "cannot insert before an active node");

        TaskGraph chain = new TaskGraph(List.of(
                goal("a", true), goal("b", true, "a"), goal("c", true, "b")));
        chain.removeAndRewire("b");
        check(chain.total() == 2, "remove deletes the node");
        check(chain.current().orElseThrow().id().equals("a"), "first dependency is current");
        chain.activate("a");
        chain.complete("a");
        check(chain.current().orElseThrow().id().equals("c"), "dependent is rewired");
        check(chain.current().orElseThrow().dependsOn().contains("a"), "rewired dependency");
        expect(IllegalArgumentException.class, () -> chain.removeAndRewire("missing"),
                "unknown node");
    }

    private static void scopedSubgoals() {
        Subgoal first = new Subgoal("a", "first", Set.of(), List.of(), List.of(),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true,
                "com.example.a", "visual");
        Subgoal second = new Subgoal("b", "second", Set.of("a"), List.of(), List.of(),
                new Fallback(Fallback.Strategy.RETRY, "retry"), RiskClass.LOW, true,
                "com.example.b", "calendar.event");
        TaskGraph graph = TaskGraph.of(first, second);
        graph.insertBefore("b", new Subgoal("repair", "repair", Set.of(), List.of(),
                List.of(), new Fallback(Fallback.Strategy.RETRY, "retry"),
                RiskClass.LOW, true));
        graph.activate("a");
        graph.complete("a");
        check(graph.current().orElseThrow().id().equals("repair"), "repair becomes current");
        check(graph.current().orElseThrow().targetPackage().equals("com.example.b"),
                "inserted repair inherits target scope");
        check(graph.current().orElseThrow().verifierId().equals("calendar.event"),
                "inserted repair inherits verifier");
        graph.activate("repair");
        graph.complete("repair");
        check(graph.current().orElseThrow().targetPackage().equals("com.example.b"),
                "original scope preserved");
        check(graph.current().orElseThrow().verifierId().equals("calendar.event"),
                "original verifier preserved");

        TaskGraph chain = TaskGraph.of(first, second);
        chain.removeAndRewire("a");
        check(chain.current().orElseThrow().targetPackage().equals("com.example.b"),
                "rewired scope preserved");
        check(chain.current().orElseThrow().verifierId().equals("calendar.event"),
                "rewired verifier preserved");
    }

    private static Subgoal goal(String id, boolean required, String... dependencies) {
        return new Subgoal(id, "intent " + id, Set.of(dependencies), List.of(), List.of(),
                new Fallback(Fallback.Strategy.ASK_USER, "ask the user"), RiskClass.LOW, required);
    }
}
