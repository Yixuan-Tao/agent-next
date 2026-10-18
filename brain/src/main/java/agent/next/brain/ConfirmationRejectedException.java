package agent.next.brain;

import java.io.Serial;

/** Raised when a confirmation token is stale, expired, or for another payload. */
public final class ConfirmationRejectedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConfirmationRejectedException(String message) {
        super(message);
    }
}
