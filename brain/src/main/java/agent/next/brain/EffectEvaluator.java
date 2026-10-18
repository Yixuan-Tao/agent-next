package agent.next.brain;

import java.util.Objects;

/**
 * Deterministic comparison of two stable fingerprints. Loading wins over
 * navigation: an unstable screen is not evidence of progress.
 */
public final class EffectEvaluator {

    public Effect evaluate(StateFingerprint before, StateFingerprint after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");

        if (!before.stable() || !after.stable()) {
            return Effect.loading("state is not stable");
        }
        if (!before.app().equals(after.app()) || !before.activity().equals(after.activity())) {
            return Effect.navigated(after.app() + "/" + after.activity());
        }
        if (!before.contentDigest().equals(after.contentDigest())) {
            return Effect.changed("content changed");
        }
        return Effect.noChange("no visible change");
    }
}
