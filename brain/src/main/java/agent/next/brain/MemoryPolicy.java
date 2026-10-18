package agent.next.brain;

import java.time.Duration;

/**
 * Retention policy from decision D4. These are defaults, not claims that a
 * persistence layer exists yet.
 */
public record MemoryPolicy(
        Duration workingCheckpointTtl,
        Duration episodicRetention,
        Duration semanticRevalidation,
        Duration userPreferenceRevalidation,
        Duration evidenceRetention,
        boolean evidenceOptIn) {

    public MemoryPolicy {
        Preconditions.requirePositive(workingCheckpointTtl, "workingCheckpointTtl");
        Preconditions.requirePositive(episodicRetention, "episodicRetention");
        Preconditions.requirePositive(semanticRevalidation, "semanticRevalidation");
        Preconditions.requirePositive(userPreferenceRevalidation, "userPreferenceRevalidation");
        Preconditions.requirePositive(evidenceRetention, "evidenceRetention");
    }

    public static MemoryPolicy defaults() {
        return new MemoryPolicy(
                Duration.ofHours(24),
                Duration.ofDays(30),
                Duration.ofDays(90),
                Duration.ofDays(180),
                Duration.ofDays(7),
                false);
    }
}
