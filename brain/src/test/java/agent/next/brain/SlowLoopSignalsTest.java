package agent.next.brain;

import java.time.Duration;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class SlowLoopSignalsTest {

    private SlowLoopSignalsTest() {
    }

    static void run() {
        thresholdsAndPriority();
        immediateReasons();
        customBudget();
        validation();
        System.out.println("SlowLoopSignalsTest: PASS (thresholds, priority, immediate triggers)");
    }

    private static void thresholdsAndPriority() {
        Budget budget = Budget.defaults();
        check(signals(0, 0).trigger(budget).isEmpty(), "fresh task");
        check(signals(3, 0).trigger(budget).isEmpty(), "three no-effect is not enough");
        check(signals(4, 0).trigger(budget).orElseThrow() == SlowLoopReason.NO_EFFECT, "four no-effect");
        check(signals(0, 4).trigger(budget).isEmpty(), "four no-progress is not enough");
        check(signals(0, 5).trigger(budget).orElseThrow() == SlowLoopReason.NO_PROGRESS, "five no-progress");
        check(signals(4, 5).trigger(budget).orElseThrow() == SlowLoopReason.NO_EFFECT,
                "no-effect outranks no-progress");
    }

    private static void immediateReasons() {
        Budget budget = Budget.defaults();
        check(new SlowLoopSignals(2, 3, true, false, false, false, false, false)
                .trigger(budget).orElseThrow() == SlowLoopReason.GRAPH_INCONSISTENT, "graph inconsistent");
        check(new SlowLoopSignals(0, 0, false, false, true, false, false, false)
                .trigger(budget).orElseThrow() == SlowLoopReason.RISK_GATE, "risk gate");
        check(new SlowLoopSignals(0, 0, false, true, false, false, false, false)
                .trigger(budget).orElseThrow() == SlowLoopReason.NEEDS_NEW_APP, "new app");
        check(new SlowLoopSignals(0, 0, false, false, false, true, false, false)
                .trigger(budget).orElseThrow() == SlowLoopReason.AMBIGUOUS_SUCCESS, "ambiguous success");
        check(new SlowLoopSignals(0, 0, false, false, false, false, true, false)
                .trigger(budget).orElseThrow() == SlowLoopReason.BUDGET_NEAR_LIMIT, "budget near limit");
        check(new SlowLoopSignals(0, 0, false, false, false, false, false, true)
                .trigger(budget).orElseThrow() == SlowLoopReason.NODE_COMPLETE, "node complete");
    }

    private static void customBudget() {
        Budget relaxed = new Budget(10, Duration.ofMinutes(1), 5, 6);
        check(signals(4, 5).trigger(relaxed).isEmpty(), "custom thresholds not reached");
        check(signals(5, 6).trigger(relaxed).orElseThrow() == SlowLoopReason.NO_EFFECT,
                "custom no-effect threshold");
        check(signals(0, 6).trigger(relaxed).orElseThrow() == SlowLoopReason.NO_PROGRESS,
                "custom no-progress threshold");
    }

    private static void validation() {
        expect(IllegalArgumentException.class,
                () -> new SlowLoopSignals(-1, 0, false, false, false, false, false, false),
                "negative no-effect counter");
        expect(IllegalArgumentException.class,
                () -> new SlowLoopSignals(0, -1, false, false, false, false, false, false),
                "negative no-progress counter");
        expect(NullPointerException.class,
                () -> signals(0, 0).trigger(null), "null budget");
    }

    private static SlowLoopSignals signals(int noEffect, int noProgress) {
        return new SlowLoopSignals(
                noEffect, noProgress, false, false, false, false, false, false);
    }
}
