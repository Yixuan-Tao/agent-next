package agent.phone.executor;

public final class ObservationBudgetTest {
    public static void main(String[] args) {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(100);
        ObservationBudget timed = new ObservationBudget(now::get,8_000_000_100L);
        check(timed.lookup() && timed.record(1024),"a timed traversal retains observed content");
        now.addAndGet(2_100_000_000L);
        check(!timed.lookup() && timed.truncated && timed.recorded() == 1,
                "a slow traversal stops further lookups and marks retained content incomplete");
        timed.finalGuard();
        check(timed.lookups() == 2,"a truncated traversal still reserves its final window identity guard");
        ObservationBudget nearlyExpired = new ObservationBudget(now::get,now.get() + 500_000_000L);
        check(!nearlyExpired.lookup(),"remaining request time is reserved for the final guard");
        nearlyExpired.finalGuard();
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
        check(!ObservationBudget.shouldRecord(false, true, false, false, false, false,
                false, "", ""), "empty visible containers must not consume the node budget");
        check(!ObservationBudget.shouldRecord(false, false, true, false, false, false,
                false, "hidden result", ""), "hidden content must not crowd out visible results");
        check(ObservationBudget.shouldRecord(true, true, false, false, false, false,
                false, "", ""), "retain the root anchor");
        check(ObservationBudget.shouldRecord(false, true, false, false, false, false,
                false, "article title", ""), "keep visible title text");
        check(ObservationBudget.shouldRecord(false, true, false, false, false, false,
                false, "", "search"), "keep description-only controls");
        check(ObservationBudget.shouldRecord(false, true, true, false, false, false,
                false, "", ""), "keep unlabeled clickable controls");
        check(ObservationBudget.shouldRecord(false, true, false, false, true, false,
                false, "", ""), "keep scrollable ancestors");
        check(ObservationBudget.shouldRecord(false, true, false, true, false, false,
                true, "", ""), "keep redacted editable/password fields");
        String emoji = new String(Character.toChars(0x1f600));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 513; i++) text.append(emoji);
        check(ObservationBudget.limit(text).codePointCount(0, ObservationBudget.limit(text).length()) == 512,
                "text clipped on codepoint boundary");
        System.out.println("ObservationBudgetTest: PASS");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
