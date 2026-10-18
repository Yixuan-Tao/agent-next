package agent.next.brain;

import java.util.Objects;

/**
 * Risk-gate output. A confirmation is a request, not a grant: the executor
 * must present it to a human and validate the returned token before committing.
 */
public sealed interface RiskDecision
        permits RiskDecision.Allow, RiskDecision.Confirm, RiskDecision.Handoff, RiskDecision.Deny {

    record Allow() implements RiskDecision {
    }

    record Confirm(ConfirmationRequest request) implements RiskDecision {
        public Confirm {
            request = Objects.requireNonNull(request, "request");
        }
    }

    record Handoff(String reason) implements RiskDecision {
        public Handoff {
            reason = Preconditions.requireText(reason, "reason");
        }
    }

    record Deny(String reason) implements RiskDecision {
        public Deny {
            reason = Preconditions.requireText(reason, "reason");
        }
    }
}
