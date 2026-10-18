package agent.next.brain;

import java.util.Objects;
import java.util.Optional;

/**
 * Pure slow-loop trigger evaluation. The caller supplies observed signals and
 * the task budget; the first matching reason wins by priority.
 */
public record SlowLoopSignals(
        int consecutiveNoEffect,
        int consecutiveNoProgress,
        boolean graphInconsistent,
        boolean needsNewApp,
        boolean riskGateTriggered,
        boolean ambiguousSuccess,
        boolean budgetNearLimit,
        boolean nodeComplete) {

    public SlowLoopSignals {
        if (consecutiveNoEffect < 0) {
            throw new IllegalArgumentException("consecutiveNoEffect must not be negative");
        }
        if (consecutiveNoProgress < 0) {
            throw new IllegalArgumentException("consecutiveNoProgress must not be negative");
        }
    }

    public Optional<SlowLoopReason> trigger(Budget budget) {
        Objects.requireNonNull(budget, "budget");

        if (graphInconsistent) {
            return Optional.of(SlowLoopReason.GRAPH_INCONSISTENT);
        }
        if (riskGateTriggered) {
            return Optional.of(SlowLoopReason.RISK_GATE);
        }
        if (needsNewApp) {
            return Optional.of(SlowLoopReason.NEEDS_NEW_APP);
        }
        if (ambiguousSuccess) {
            return Optional.of(SlowLoopReason.AMBIGUOUS_SUCCESS);
        }
        if (consecutiveNoEffect >= budget.maxConsecutiveNoEffect()) {
            return Optional.of(SlowLoopReason.NO_EFFECT);
        }
        if (consecutiveNoProgress >= budget.maxConsecutiveNoProgress()) {
            return Optional.of(SlowLoopReason.NO_PROGRESS);
        }
        if (budgetNearLimit) {
            return Optional.of(SlowLoopReason.BUDGET_NEAR_LIMIT);
        }
        if (nodeComplete) {
            return Optional.of(SlowLoopReason.NODE_COMPLETE);
        }
        return Optional.empty();
    }
}
