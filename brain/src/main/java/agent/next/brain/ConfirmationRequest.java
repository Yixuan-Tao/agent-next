package agent.next.brain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * What the human is asked to approve. The payload hash binds the approval to
 * the exact prepared action; editing content, recipient, or amount invalidates
 * it (decision D2).
 */
public record ConfirmationRequest(
        String actionId,
        String summary,
        String consequences,
        boolean reversible,
        String payloadHash) {

    public ConfirmationRequest {
        actionId = Preconditions.requireText(actionId, "actionId");
        summary = Preconditions.requireText(summary, "summary");
        consequences = Preconditions.requireText(consequences, "consequences");
        payloadHash = Preconditions.requireText(payloadHash, "payloadHash");
    }

    public ConfirmationToken confirm(Instant now, Duration validity) {
        Objects.requireNonNull(now, "now");
        Preconditions.requirePositive(validity, "validity");
        return new ConfirmationToken(actionId, payloadHash, now, now.plus(validity));
    }
}
