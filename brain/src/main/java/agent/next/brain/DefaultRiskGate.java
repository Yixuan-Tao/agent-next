package agent.next.brain;

import java.util.Objects;

/**
 * Rule-first risk gate implementing decisions D1–D3.
 *
 * <p>Credentials and legal/medical decisions always hand off. Commitments at
 * A3 require confirmation; at A2 they hand off because the agent is not
 * allowed to make the commitment; below A2 they are denied. External messages
 * always require confirmation, even if some future caller marks them low risk.</p>
 */
public final class DefaultRiskGate implements RiskGate {

    @Override
    public RiskDecision evaluate(ActionDescriptor action, TaskSpec task) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(task, "task");

        if (task.denies(action.id())) {
            return new RiskDecision.Deny("action is denied by task policy: " + action.id());
        }
        if (action.handlesCredential()) {
            return new RiskDecision.Handoff(
                    "credentials, OTP, passkeys, and payment secrets require a human");
        }
        if (action.risk() == RiskClass.LEGAL_MEDICAL) {
            return new RiskDecision.Handoff("legal or medical decisions require a human");
        }

        boolean commitmentLike = isCommitmentLike(action);
        if (commitmentLike && !task.autonomy().level().atLeast(AutonomyLevel.A3_CONFIRM_COMMIT)) {
            if (task.autonomy().level().atLeast(AutonomyLevel.A2_REVERSIBLE)) {
                return new RiskDecision.Handoff(
                        "autonomy " + task.autonomy().level() + " stops before commitment");
            }
            return new RiskDecision.Deny(
                    "autonomy " + task.autonomy().level() + " does not permit commitment");
        }

        AutonomyPolicy.Decision autonomy = AutonomyPolicy.decide(
                task.autonomy(), action.kind(), action.id());
        if (autonomy == AutonomyPolicy.Decision.DENY) {
            return new RiskDecision.Deny(
                    "autonomy " + task.autonomy().level() + " does not permit " + action.kind());
        }
        if (autonomy == AutonomyPolicy.Decision.CONFIRM) {
            return new RiskDecision.Confirm(confirmation(action));
        }
        if (commitmentLike) {
            return new RiskDecision.Confirm(confirmation(action));
        }
        return new RiskDecision.Allow();
    }

    private static boolean isCommitmentLike(ActionDescriptor action) {
        return action.kind() == ActionKind.COMMITMENT
                || action.sendsExternalMessage()
                || action.destructive()
                || action.risk() == RiskClass.COMMITMENT;
    }

    private static ConfirmationRequest confirmation(ActionDescriptor action) {
        boolean reversible = action.kind() != ActionKind.COMMITMENT && !action.destructive();
        String consequences = "target=" + action.targetApp()
                + "; risk=" + action.risk()
                + "; flags=" + action.flags()
                + (reversible ? "; reversible" : "; irreversible or externally visible");
        return new ConfirmationRequest(
                action.id(), action.description(), consequences, reversible, action.payloadHash());
    }
}
