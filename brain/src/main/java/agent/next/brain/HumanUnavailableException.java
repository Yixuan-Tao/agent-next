package agent.next.brain;

import java.io.Serial;

/** Raised when no human is available for a required confirmation or handoff. */
public final class HumanUnavailableException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public HumanUnavailableException(String message) {
        super(message);
    }
}
