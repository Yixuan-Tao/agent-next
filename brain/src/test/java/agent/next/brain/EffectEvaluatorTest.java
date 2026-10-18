package agent.next.brain;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class EffectEvaluatorTest {

    private EffectEvaluatorTest() {
    }

    static void run() {
        comparisons();
        loadingWins();
        validation();
        System.out.println("EffectEvaluatorTest: PASS (changed, no-change, loading, navigated, error)");
    }

    private static void comparisons() {
        EffectEvaluator evaluator = new EffectEvaluator();
        StateFingerprint before = new StateFingerprint("shop", "MainActivity", "digest-1", true);

        check(evaluator.evaluate(before, before).kind() == Effect.EffectKind.NO_CHANGE, "same state");
        check(evaluator.evaluate(before,
                new StateFingerprint("shop", "MainActivity", "digest-2", true)).kind() == Effect.EffectKind.CHANGED,
                "digest change");
        check(evaluator.evaluate(before,
                new StateFingerprint("shop", "DetailActivity", "digest-1", true)).kind() == Effect.EffectKind.NAVIGATED,
                "activity change");
        check(evaluator.evaluate(before,
                new StateFingerprint("calendar", "MainActivity", "digest-1", true)).kind() == Effect.EffectKind.NAVIGATED,
                "app change");
    }

    private static void loadingWins() {
        EffectEvaluator evaluator = new EffectEvaluator();
        StateFingerprint stable = new StateFingerprint("shop", "MainActivity", "digest-1", true);
        StateFingerprint loading = new StateFingerprint("shop", "DetailActivity", "digest-2", false);

        check(evaluator.evaluate(stable, loading).kind() == Effect.EffectKind.LOADING,
                "unstable after wins over navigation");
        check(evaluator.evaluate(loading, stable).kind() == Effect.EffectKind.LOADING,
                "unstable before is not evidence");
    }

    private static void validation() {
        check(Effect.error("boom", true).failed(), "error is failure");
        check(Effect.error("boom", true).retryable(), "error retryable");
        check(Effect.changed("x").kind() == Effect.EffectKind.CHANGED, "changed factory");
        expect(IllegalArgumentException.class,
                () -> new Effect(Effect.EffectKind.CHANGED, "x", true), "retryable only for errors");
        expect(IllegalArgumentException.class,
                () -> new Effect(Effect.EffectKind.CHANGED, " ", false), "blank detail");
        expect(NullPointerException.class,
                () -> new StateFingerprint(null, "a", "d", true), "null app");
        expect(IllegalArgumentException.class,
                () -> new StateFingerprint(" ", "a", "d", true), "blank app");
        EffectEvaluator evaluator = new EffectEvaluator();
        expect(NullPointerException.class,
                () -> evaluator.evaluate(null, new StateFingerprint("a", "b", "c", true)), "null before");
    }
}
