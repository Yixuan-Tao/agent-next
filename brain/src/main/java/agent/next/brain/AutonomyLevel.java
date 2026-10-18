package agent.next.brain;

/**
 * Task-level autonomy ceiling, as defined by decision D1.
 *
 * <p>Levels are cumulative for A0–A3. A4 does not lift the commitment
 * boundary; it only allows explicitly authorized reversible actions to run
 * without a per-step prompt. A4 is rejected by {@link TaskSpec} in v1.</p>
 */
public enum AutonomyLevel {
    A0_READ_ONLY,
    A1_NAVIGATE,
    A2_REVERSIBLE,
    A3_CONFIRM_COMMIT,
    A4_AUTHORIZED_AUTO;

    public boolean atLeast(AutonomyLevel other) {
        return ordinal() >= other.ordinal();
    }
}
