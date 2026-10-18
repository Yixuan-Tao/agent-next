package agent.next.brain;

import java.util.List;
import java.util.Set;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class RiskGateTest {

    private RiskGateTest() {
    }

    static void run() {
        lowRiskAndReversible();
        commitments();
        messages();
        credentialsAndLegal();
        destructiveAndDenied();
        nulls();
        System.out.println("RiskGateTest: PASS (allow, confirm, handoff, deny)");
    }

    private static void lowRiskAndReversible() {
        DefaultRiskGate gate = new DefaultRiskGate();
        TaskSpec a3 = task(AutonomyLevel.A3_CONFIRM_COMMIT, Set.of());

        check(gate.evaluate(action("look", ActionKind.NAVIGATE, RiskClass.LOW, Set.of()), a3)
                instanceof RiskDecision.Allow, "navigation allowed");
        check(gate.evaluate(action("cart", ActionKind.REVERSIBLE_WRITE,
                RiskClass.REVERSIBLE_SIDE_EFFECT, Set.of()), a3) instanceof RiskDecision.Allow,
                "reversible write allowed at A3");
        check(gate.evaluate(action("cart", ActionKind.REVERSIBLE_WRITE,
                RiskClass.REVERSIBLE_SIDE_EFFECT, Set.of()), task(AutonomyLevel.A2_REVERSIBLE, Set.of()))
                instanceof RiskDecision.Allow, "reversible write allowed at A2");
    }

    private static void commitments() {
        DefaultRiskGate gate = new DefaultRiskGate();
        ActionDescriptor order = action("order", ActionKind.COMMITMENT, RiskClass.COMMITMENT, Set.of());

        RiskDecision decision = gate.evaluate(order, task(AutonomyLevel.A3_CONFIRM_COMMIT, Set.of()));
        check(decision instanceof RiskDecision.Confirm, "A3 commitment confirms");
        ConfirmationRequest request = ((RiskDecision.Confirm) decision).request();
        check(request.actionId().equals("order"), "confirmation action id");
        check(request.payloadHash().equals("hash-order"), "confirmation binds payload");
        check(!request.reversible(), "commitment is not reversible");
        check(request.consequences().contains("target=com.example.shop"), "confirmation shows target");

        check(gate.evaluate(order, task(AutonomyLevel.A2_REVERSIBLE, Set.of()))
                instanceof RiskDecision.Handoff, "A2 commitment hands off");
        check(gate.evaluate(order, task(AutonomyLevel.A0_READ_ONLY, Set.of()))
                instanceof RiskDecision.Deny, "A0 commitment denied");
        check(gate.evaluate(order, task(AutonomyLevel.A1_NAVIGATE, Set.of()))
                instanceof RiskDecision.Deny, "A1 commitment denied");
    }

    private static void messages() {
        DefaultRiskGate gate = new DefaultRiskGate();
        TaskSpec a3 = task(AutonomyLevel.A3_CONFIRM_COMMIT, Set.of());

        check(gate.evaluate(action("send", ActionKind.COMMITMENT, RiskClass.LOW,
                Set.of(ActionFlag.EXTERNAL_MESSAGE)), a3) instanceof RiskDecision.Confirm,
                "message confirms even when risk class is low");
        check(gate.evaluate(action("send", ActionKind.REVERSIBLE_WRITE, RiskClass.LOW,
                Set.of(ActionFlag.EXTERNAL_MESSAGE)), a3) instanceof RiskDecision.Confirm,
                "message flag makes a reversible action commitment-like");
        check(gate.evaluate(action("send", ActionKind.COMMITMENT, RiskClass.LOW,
                Set.of(ActionFlag.EXTERNAL_MESSAGE)), task(AutonomyLevel.A2_REVERSIBLE, Set.of()))
                instanceof RiskDecision.Handoff, "A2 message hands off");
    }

    private static void credentialsAndLegal() {
        DefaultRiskGate gate = new DefaultRiskGate();
        TaskSpec a3 = task(AutonomyLevel.A3_CONFIRM_COMMIT, Set.of());

        check(gate.evaluate(action("login", ActionKind.REVERSIBLE_WRITE, RiskClass.CREDENTIAL,
                Set.of(ActionFlag.CREDENTIAL)), a3) instanceof RiskDecision.Handoff,
                "credential always hands off");
        check(gate.evaluate(action("sign", ActionKind.COMMITMENT, RiskClass.LEGAL_MEDICAL,
                Set.of()), a3) instanceof RiskDecision.Handoff,
                "legal/medical always hands off");
    }

    private static void destructiveAndDenied() {
        DefaultRiskGate gate = new DefaultRiskGate();
        TaskSpec a3 = task(AutonomyLevel.A3_CONFIRM_COMMIT, Set.of());

        RiskDecision decision = gate.evaluate(action("delete", ActionKind.COMMITMENT,
                RiskClass.COMMITMENT, Set.of(ActionFlag.DESTRUCTIVE)), a3);
        check(decision instanceof RiskDecision.Confirm, "destructive confirms");
        check(!((RiskDecision.Confirm) decision).request().reversible(), "destructive is irreversible");
        check(gate.evaluate(action("delete", ActionKind.REVERSIBLE_WRITE, RiskClass.LOW,
                Set.of(ActionFlag.DESTRUCTIVE)), task(AutonomyLevel.A2_REVERSIBLE, Set.of()))
                instanceof RiskDecision.Handoff, "A2 destructive hands off");

        TaskSpec denied = task(AutonomyLevel.A3_CONFIRM_COMMIT, Set.of("order"));
        check(gate.evaluate(action("order", ActionKind.COMMITMENT, RiskClass.COMMITMENT, Set.of()), denied)
                instanceof RiskDecision.Deny, "task-level denial wins");
    }

    private static void nulls() {
        DefaultRiskGate gate = new DefaultRiskGate();
        TaskSpec a3 = task(AutonomyLevel.A3_CONFIRM_COMMIT, Set.of());
        ActionDescriptor order = action("order", ActionKind.COMMITMENT, RiskClass.COMMITMENT, Set.of());
        expect(NullPointerException.class, () -> gate.evaluate(null, a3), "null action");
        expect(NullPointerException.class, () -> gate.evaluate(order, null), "null task");
    }

    private static TaskSpec task(AutonomyLevel level, Set<String> deniedActions) {
        return new TaskSpec(
                "task-1",
                "checkout",
                List.of(),
                List.of(new SuccessCriterion("done", "order prepared", true)),
                AutonomyGrant.of(level),
                Set.of("com.example.shop"),
                deniedActions,
                Budget.defaults());
    }

    private static ActionDescriptor action(
            String id, ActionKind kind, RiskClass risk, Set<ActionFlag> flags) {
        return new ActionDescriptor(id, "do " + id, kind, risk, "com.example.shop", "hash-" + id, flags);
    }
}
