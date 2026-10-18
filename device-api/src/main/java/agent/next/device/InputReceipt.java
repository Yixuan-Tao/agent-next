package agent.next.device;

import java.time.Instant;
import java.util.Optional;

/**
 * Receipt for one input request. For text, {@code verified} means the
 * implementation read the field back; a bare injection is not app success.
 */
public final class InputReceipt {

    private final InputStatus status;
    private final boolean accepted;
    private final boolean verified;
    private final String actualText;
    private final Instant at;

    public InputReceipt(InputStatus status, boolean accepted, boolean verified,
            String actualText, Instant at) {
        if (status == null) {
            throw new NullPointerException("status");
        }
        if (at == null) {
            throw new NullPointerException("at");
        }
        if (status == InputStatus.REJECTED && accepted) {
            throw new IllegalArgumentException("rejected receipt cannot be accepted");
        }
        if (status == InputStatus.VERIFIED && !verified) {
            throw new IllegalArgumentException("verified receipt must be verified");
        }
        if (status == InputStatus.INJECTED && verified) {
            throw new IllegalArgumentException("injected receipt is not a verified effect");
        }
        if (status == InputStatus.INJECTED && !accepted) {
            throw new IllegalArgumentException("injected receipt must be accepted");
        }
        this.status = status;
        this.accepted = accepted;
        this.verified = verified;
        this.actualText = actualText;
        this.at = at;
    }

    public InputStatus status() {
        return status;
    }

    public boolean accepted() {
        return accepted;
    }

    public boolean verified() {
        return verified;
    }

    public Optional<String> actualText() {
        return Optional.ofNullable(actualText);
    }

    public Instant at() {
        return at;
    }

    @Override
    public String toString() {
        return "InputReceipt{" + status + ", accepted=" + accepted
                + ", verified=" + verified + '}';
    }
}
