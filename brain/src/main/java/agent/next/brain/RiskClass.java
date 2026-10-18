package agent.next.brain;

/** Risk classification used by the default risk gate. */
public enum RiskClass {
    LOW,
    REVERSIBLE_SIDE_EFFECT,
    COMMITMENT,
    CREDENTIAL,
    LEGAL_MEDICAL
}
