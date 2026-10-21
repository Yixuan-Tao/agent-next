package agent.next.brain;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class TaskSpecTest {

    private TaskSpecTest() {
    }

    static void run() {
        validSpec();
        a4IsDisabled();
        successCriteria();
        appScope();
        immutability();
        budgetValidation();
        System.out.println("TaskSpecTest: PASS (envelope, A4 gate, criteria, scopes, budget)");
    }

    private static void validSpec() {
        TaskSpec spec = spec(AutonomyLevel.A3_CONFIRM_COMMIT);
        check(spec.id().equals("task-1"), "id");
        check(spec.goal().equals("buy a shirt"), "goal");
        check(spec.constraints().equals(List.of("budget <= 200")), "constraints");
        check(spec.successCriteria().size() == 1, "success criteria");
        check(spec.allowedApps().contains("com.example.shop"), "allowed app");
        check(spec.autonomy().level() == AutonomyLevel.A3_CONFIRM_COMMIT, "autonomy");
        check(spec.budget().equals(Budget.defaults()), "budget");
        check(!spec.denies("anything"), "nothing denied by default");
    }

    private static void a4IsDisabled() {
        AutonomyGrant a4 = new AutonomyGrant(
                AutonomyLevel.A4_AUTHORIZED_AUTO, Set.of("add_to_cart"));
        expect(IllegalArgumentException.class,
                () -> new TaskSpec("task-1", "buy a shirt", List.of("budget <= 200"),
                        List.of(new SuccessCriterion("cart", "item in cart", true)),
                        a4, Set.of("com.example.shop"), Set.of(), Budget.defaults()),
                "A4 rejected in v1");
    }

    private static void successCriteria() {
        expect(IllegalArgumentException.class,
                () -> new TaskSpec("task-1", "goal", List.of(), List.of(),
                        AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE),
                        Set.of("com.example.shop"), Set.of(), Budget.defaults()),
                "empty success criteria");
        expect(IllegalArgumentException.class,
                () -> new TaskSpec("task-1", "goal", List.of(),
                        List.of(new SuccessCriterion("soft", "optional", false)),
                        AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE),
                        Set.of("com.example.shop"), Set.of(), Budget.defaults()),
                "at least one required criterion");
    }

    private static void appScope() {
        expect(IllegalArgumentException.class,
                () -> new TaskSpec("task-1", "goal", List.of(),
                        List.of(new SuccessCriterion("done", "done", true)),
                        AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE),
                        Set.of(), Set.of(), Budget.defaults()),
                "empty app scope");
        expect(IllegalArgumentException.class,
                () -> new TaskSpec("task-1", "goal", List.of(),
                        List.of(new SuccessCriterion("done", "done", true)),
                        AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE),
                        Set.of(" "), Set.of(), Budget.defaults()),
                "blank app id");
    }

    private static void immutability() {
        TaskSpec spec = spec(AutonomyLevel.A3_CONFIRM_COMMIT);
        expect(UnsupportedOperationException.class, () -> spec.constraints().add("new"), "constraints immutable");
        expect(UnsupportedOperationException.class, () -> spec.allowedApps().add("new.app"), "apps immutable");
        expect(UnsupportedOperationException.class, () -> spec.deniedActions().add("new.action"), "denials immutable");

        TaskSpec denied = new TaskSpec("task-2", "goal", List.of(),
                List.of(new SuccessCriterion("done", "done", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of("pay"), Budget.defaults());
        check(denied.denies("pay"), "task-level denial");
        check(!denied.denies("send"), "only listed action denied");
        check(!denied.denies(null), "null action is not denied");
    }

    private static void budgetValidation() {
        expect(IllegalArgumentException.class,
                () -> new Budget(0, Duration.ofMinutes(1), 2, 3), "zero action budget");
        expect(IllegalArgumentException.class,
                () -> new Budget(1, Duration.ZERO, 2, 3), "zero duration budget");
        expect(IllegalArgumentException.class,
                () -> new Budget(1, Duration.ofMinutes(1), 0, 3), "zero no-effect threshold");
        expect(IllegalArgumentException.class,
                () -> new Budget(1, Duration.ofMinutes(1), 2, 0), "zero no-progress threshold");
        check(Budget.defaults().maxActions() == 40, "default action budget");
        check(Budget.defaults().maxDuration().equals(Duration.ofMinutes(10)), "default duration");
        check(Budget.defaults().maxConsecutiveNoEffect() == 4, "default no-effect threshold");
        check(Budget.defaults().maxConsecutiveNoProgress() == 5, "default no-progress threshold");
    }

    private static TaskSpec spec(AutonomyLevel level) {
        return new TaskSpec(
                "task-1",
                "buy a shirt",
                List.of("budget <= 200"),
                List.of(new SuccessCriterion("cart", "item is in cart", true)),
                AutonomyGrant.of(level),
                Set.of("com.example.shop"),
                Set.of(),
                Budget.defaults());
    }
}
