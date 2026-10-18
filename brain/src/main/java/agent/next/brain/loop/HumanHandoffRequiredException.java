package agent.next.brain.loop;

/** A policy detected a human-verification/risk boundary and must not act. */
public final class HumanHandoffRequiredException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public HumanHandoffRequiredException(String message) {
        super(message);
    }
}
