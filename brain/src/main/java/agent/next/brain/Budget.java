package agent.next.brain;

import java.time.Duration;

/**
 * Hard task budget. Thresholds are upper bounds; the slow loop may stop
 * earlier. The no-effect/no-progress thresholds are also used by
 * {@link SlowLoopSignals}.
 */
public record Budget(
        int maxActions,
        Duration maxDuration,
        int maxConsecutiveNoEffect,
        int maxConsecutiveNoProgress) {

    public Budget {
        Preconditions.requirePositive(maxActions, "maxActions");
        Preconditions.requirePositive(maxDuration, "maxDuration");
        Preconditions.requirePositive(maxConsecutiveNoEffect, "maxConsecutiveNoEffect");
        Preconditions.requirePositive(maxConsecutiveNoProgress, "maxConsecutiveNoProgress");
    }

    public static Budget defaults() {
        return new Budget(40, Duration.ofMinutes(10), 2, 3);
    }

    @Override
    public String toString() {
        return "Budget{maxActions=" + maxActions
                + ", maxDuration=" + maxDuration
                + ", maxConsecutiveNoEffect=" + maxConsecutiveNoEffect
                + ", maxConsecutiveNoProgress=" + maxConsecutiveNoProgress
                + '}';
    }
}
