package agent.phone.executor;

public final class ObservationBudgetTest {
    public static void main(String[] args) {
        ObservationBudget budget = new ObservationBudget();
        for (int i = 0; i < 999; i++) check(budget.lookup(), "normal lookup within reserved final guard");
        check(!budget.lookup() && budget.truncated, "reserve final root guard before traversing");
        budget.finalGuard();
        check(budget.lookups() == 1000, "root+child+guard total exactly bounded");
        try { budget.finalGuard(); throw new AssertionError("extra final guard accepted"); }
        catch (IllegalStateException expected) { }
        ObservationBudget bytes = new ObservationBudget();
        check(!bytes.record(ObservationBudget.MAX_CONTENT_BYTES + 1), "oversized node rejected");
        check(bytes.truncated && bytes.recorded() == 0, "rejection cannot pretend complete");
        String emoji = new String(Character.toChars(0x1f600));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 513; i++) text.append(emoji);
        check(ObservationBudget.limit(text).codePointCount(0, ObservationBudget.limit(text).length()) == 512,
                "text clipped on codepoint boundary");
        System.out.println("ObservationBudgetTest: PASS");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
