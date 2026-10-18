package agent.next.brain;

import java.time.Instant;
import java.util.Objects;

/** A human approval bound to one action id and one payload hash. */
public record ConfirmationToken(
        String actionId,
        String payloadHash,
        Instant issuedAt,
        Instant expiresAt) {

    public ConfirmationToken {
        actionId = Preconditions.requireText(actionId, "actionId");
        payloadHash = Preconditions.requireText(payloadHash, "payloadHash");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
    }

    public boolean matches(ConfirmationRequest request) {
        Objects.requireNonNull(request, "request");
        return actionId.equals(request.actionId()) && payloadHash.equals(request.payloadHash());
    }

    public boolean isValidAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(issuedAt) && now.isBefore(expiresAt);
    }

    public void requireValidFor(ConfirmationRequest request, Instant now) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(now, "now");
        if (!matches(request)) {
            throw new ConfirmationRejectedException(
                    "confirmation does not match action/payload; content may have changed");
        }
        if (!isValidAt(now)) {
            throw new ConfirmationRejectedException("confirmation has expired or is not yet valid");
        }
    }
}
