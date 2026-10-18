package agent.next.brain;

/**
 * What an action does to the device, independent of how risky it is.
 *
 * <p>Autonomy is evaluated against this kind; risk is evaluated separately by
 * {@link RiskGate}. A commitment is an externally visible or irreversible
 * effect, such as paying, sending, deleting, or posting.</p>
 */
public enum ActionKind {
    OBSERVE,
    NAVIGATE,
    REVERSIBLE_WRITE,
    COMMITMENT
}
