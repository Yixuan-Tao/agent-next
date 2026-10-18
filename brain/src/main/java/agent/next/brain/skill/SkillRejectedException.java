package agent.next.brain.skill;

/** Raised when an untrusted or unvalidated skill is refused admission. */
public final class SkillRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SkillRejectedException(String message) {
        super(message);
    }
}
