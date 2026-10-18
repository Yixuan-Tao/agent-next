package agent.next.brain;

import java.util.Objects;
import java.util.Set;

/**
 * The exact action the brain wants to perform. {@code payloadHash} must cover
 * the concrete content (text, amount, recipient, target) so a confirmation
 * cannot be reused after the content changes.
 */
public record ActionDescriptor(
        String id,
        String description,
        ActionKind kind,
        RiskClass risk,
        String targetApp,
        String payloadHash,
        Set<ActionFlag> flags) {

    public ActionDescriptor {
        id = Preconditions.requireText(id, "id");
        description = Preconditions.requireText(description, "description");
        kind = Objects.requireNonNull(kind, "kind");
        risk = Objects.requireNonNull(risk, "risk");
        targetApp = Preconditions.requireText(targetApp, "targetApp");
        payloadHash = Preconditions.requireText(payloadHash, "payloadHash");
        Objects.requireNonNull(flags, "flags");
        flags = Set.copyOf(flags);
    }

    public boolean sendsExternalMessage() {
        return flags.contains(ActionFlag.EXTERNAL_MESSAGE);
    }

    public boolean handlesCredential() {
        return flags.contains(ActionFlag.CREDENTIAL) || risk == RiskClass.CREDENTIAL;
    }

    public boolean destructive() {
        return flags.contains(ActionFlag.DESTRUCTIVE);
    }
}
