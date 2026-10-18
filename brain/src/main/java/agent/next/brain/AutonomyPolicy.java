package agent.next.brain;

import java.util.Objects;

/**
 * Pure autonomy matrix. It answers whether the task may attempt an action;
 * it does not decide whether a commitment is safe. That remains the job of
 * {@link RiskGate}, which can always escalate to confirm, handoff, or deny.
 */
public final class AutonomyPolicy {

    public enum Decision {
        ALLOW,
        CONFIRM,
        DENY
    }

    private AutonomyPolicy() {
    }

    public static Decision decide(AutonomyGrant grant, ActionKind kind, String actionId) {
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(kind, "kind");

        if (kind == ActionKind.OBSERVE) {
            return Decision.ALLOW;
        }

        return switch (grant.level()) {
            case A0_READ_ONLY -> Decision.DENY;
            case A1_NAVIGATE -> kind == ActionKind.NAVIGATE
                    ? Decision.ALLOW
                    : Decision.DENY;
            case A2_REVERSIBLE -> kind == ActionKind.NAVIGATE
                    || kind == ActionKind.REVERSIBLE_WRITE
                    ? Decision.ALLOW
                    : Decision.DENY;
            case A3_CONFIRM_COMMIT -> kind == ActionKind.COMMITMENT
                    ? Decision.CONFIRM
                    : Decision.ALLOW;
            case A4_AUTHORIZED_AUTO -> {
                if (kind == ActionKind.COMMITMENT) {
                    yield Decision.CONFIRM;
                }
                if (kind == ActionKind.REVERSIBLE_WRITE) {
                    yield grant.authorizes(actionId) ? Decision.ALLOW : Decision.CONFIRM;
                }
                yield Decision.ALLOW;
            }
        };
    }
}
