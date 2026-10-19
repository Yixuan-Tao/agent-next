package agent.next.brain;

import agent.next.brain.action.GuiRiskClassifier;

import java.util.Objects;

/**
 * Rule-first risk gate implementing decisions D1–D3.
 *
 * <p>Credentials and legal/medical decisions always hand off. Commitments at
 * A3 require confirmation; at A2 they hand off because the agent is not
 * allowed to make the commitment; below A2 they are denied. External messages
 * always require confirmation, even if some future caller marks them low risk.</p>
 *
 * <p>Before evaluating, {@link GuiRiskClassifier} can only raise the model's
 * declared kind/risk from the actual action text. This is the first line that
 * prevents a model from labelling "立即支付" as LOW.</p>
 */
public final class DefaultRiskGate implements RiskGate {

    @Override
    public RiskDecision evaluate(ActionDescriptor action, TaskSpec task) {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(task, "task");
        ActionDescriptor effective = GuiRiskClassifier.raise(action);

        if (task.denies(effective.id())) {
            return new RiskDecision.Deny("action is denied by task policy: " + effective.id());
        }
        GuiRiskClassifier.Category category = GuiRiskClassifier.category(effective);
        if ((category == GuiRiskClassifier.Category.PAYMENT
                && (task.denies("payment") || task.denies("place_order")))
                || (category == GuiRiskClassifier.Category.MESSAGE && task.denies("send"))
                || (category == GuiRiskClassifier.Category.DESTRUCTIVE && task.denies("delete"))
                || (category == GuiRiskClassifier.Category.INSTALL && task.denies("install"))) {
            return new RiskDecision.Deny("action category is denied by task policy: " + category);
        }
        if (effective.handlesCredential()) {
            return new RiskDecision.Handoff(
                    "credentials, OTP, passkeys, and payment secrets require a human");
        }
        if (effective.risk() == RiskClass.LEGAL_MEDICAL) {
            return new RiskDecision.Handoff("legal or medical decisions require a human");
        }

        boolean commitmentLike = isCommitmentLike(effective);
        if (commitmentLike && !task.autonomy().level().atLeast(AutonomyLevel.A3_CONFIRM_COMMIT)) {
            if (task.autonomy().level().atLeast(AutonomyLevel.A2_REVERSIBLE)) {
                return new RiskDecision.Handoff(
                        "autonomy " + task.autonomy().level() + " stops before commitment");
            }
            return new RiskDecision.Deny(
                    "autonomy " + task.autonomy().level() + " does not permit commitment");
        }

        AutonomyPolicy.Decision autonomy = AutonomyPolicy.decide(
                task.autonomy(), effective.kind(), effective.id());
        if (autonomy == AutonomyPolicy.Decision.DENY) {
            return new RiskDecision.Deny(
                    "autonomy " + task.autonomy().level() + " does not permit " + effective.kind());
        }
        if (autonomy == AutonomyPolicy.Decision.CONFIRM) {
            return new RiskDecision.Confirm(confirmation(effective));
        }
        if (commitmentLike) {
            return new RiskDecision.Confirm(confirmation(effective));
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
