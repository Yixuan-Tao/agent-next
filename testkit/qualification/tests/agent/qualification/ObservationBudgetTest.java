package agent.qualification;

public final class ObservationBudgetTest {
    private static int checks;

    private ObservationBudgetTest() {
    }

    public static void main(String[] args) {
        ObservationBudget budget = new ObservationBudget();
        budget.requireLookup(); // Initial window root.
        for (int i = 1; i < ObservationBudget.MAX_LOOKUPS - 1; i++) {
            check("child lookup within limit", budget.reserve());
        }
        budget.requireLookup(); // Final focused-window root shares the same budget.
        check("exact limit is accepted", budget.lookups() == ObservationBudget.MAX_LOOKUPS);
        rejects("another root cannot bypass the total limit", budget);
        check("exhaustion marks truncation", budget.truncated);
        check("child lookup is also rejected after root exhaustion", !budget.reserve());
        check("failed reservations do not advance the total", budget.lookups() == ObservationBudget.MAX_LOOKUPS);
        rejects("repeated final validation still fails closed", budget);
        System.out.println("ObservationBudgetTest: " + checks + " passed, 0 failed");
    }

    private static void check(String scenario, boolean condition) {
        checks++;
        if (!condition) {
            throw new AssertionError(scenario);
        }
    }

    private static void rejects(String scenario, ObservationBudget budget) {
        checks++;
        try {
            budget.requireLookup();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(scenario);
    }
}
