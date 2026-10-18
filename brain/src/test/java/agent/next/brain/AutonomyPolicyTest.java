package agent.next.brain;

import java.util.Set;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class AutonomyPolicyTest {

    private AutonomyPolicyTest() {
    }

    static void run() {
        readOnly();
        navigate();
        reversible();
        confirmCommit();
        authorizedAuto();
        grantValidation();
        System.out.println("AutonomyPolicyTest: PASS (A0-A4 matrix, explicit A4 grants)");
    }

    private static void readOnly() {
        AutonomyGrant grant = AutonomyGrant.of(AutonomyLevel.A0_READ_ONLY);
        check(decide(grant, ActionKind.OBSERVE) == AutonomyPolicy.Decision.ALLOW, "A0 observe");
        check(decide(grant, ActionKind.NAVIGATE) == AutonomyPolicy.Decision.DENY, "A0 navigate");
        check(decide(grant, ActionKind.REVERSIBLE_WRITE) == AutonomyPolicy.Decision.DENY, "A0 write");
        check(decide(grant, ActionKind.COMMITMENT) == AutonomyPolicy.Decision.DENY, "A0 commit");
    }

    private static void navigate() {
        AutonomyGrant grant = AutonomyGrant.of(AutonomyLevel.A1_NAVIGATE);
        check(decide(grant, ActionKind.OBSERVE) == AutonomyPolicy.Decision.ALLOW, "A1 observe");
        check(decide(grant, ActionKind.NAVIGATE) == AutonomyPolicy.Decision.ALLOW, "A1 navigate");
        check(decide(grant, ActionKind.REVERSIBLE_WRITE) == AutonomyPolicy.Decision.DENY, "A1 write");
        check(decide(grant, ActionKind.COMMITMENT) == AutonomyPolicy.Decision.DENY, "A1 commit");
    }

    private static void reversible() {
        AutonomyGrant grant = AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE);
        check(decide(grant, ActionKind.OBSERVE) == AutonomyPolicy.Decision.ALLOW, "A2 observe");
        check(decide(grant, ActionKind.NAVIGATE) == AutonomyPolicy.Decision.ALLOW, "A2 navigate");
        check(decide(grant, ActionKind.REVERSIBLE_WRITE) == AutonomyPolicy.Decision.ALLOW, "A2 write");
        check(decide(grant, ActionKind.COMMITMENT) == AutonomyPolicy.Decision.DENY, "A2 commit denied");
    }

    private static void confirmCommit() {
        AutonomyGrant grant = AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT);
        check(decide(grant, ActionKind.OBSERVE) == AutonomyPolicy.Decision.ALLOW, "A3 observe");
        check(decide(grant, ActionKind.NAVIGATE) == AutonomyPolicy.Decision.ALLOW, "A3 navigate");
        check(decide(grant, ActionKind.REVERSIBLE_WRITE) == AutonomyPolicy.Decision.ALLOW, "A3 write");
        check(decide(grant, ActionKind.COMMITMENT) == AutonomyPolicy.Decision.CONFIRM, "A3 commit confirms");
    }

    private static void authorizedAuto() {
        AutonomyGrant grant = new AutonomyGrant(
                AutonomyLevel.A4_AUTHORIZED_AUTO, Set.of("add_to_cart"));
        check(decide(grant, ActionKind.OBSERVE) == AutonomyPolicy.Decision.ALLOW, "A4 observe");
        check(decide(grant, ActionKind.NAVIGATE) == AutonomyPolicy.Decision.ALLOW, "A4 navigate");
        check(decide(grant, ActionKind.REVERSIBLE_WRITE, "add_to_cart") == AutonomyPolicy.Decision.ALLOW,
                "A4 authorized write");
        check(decide(grant, ActionKind.REVERSIBLE_WRITE, "other") == AutonomyPolicy.Decision.CONFIRM,
                "A4 unlisted write confirms");
        check(decide(grant, ActionKind.COMMITMENT, "send") == AutonomyPolicy.Decision.CONFIRM,
                "A4 commitment still confirms");
    }

    private static void grantValidation() {
        expect(IllegalArgumentException.class,
                () -> new AutonomyGrant(AutonomyLevel.A4_AUTHORIZED_AUTO, Set.of()), "A4 needs explicit actions");
        expect(IllegalArgumentException.class,
                () -> new AutonomyGrant(AutonomyLevel.A3_CONFIRM_COMMIT, Set.of("add_to_cart")),
                "non-A4 cannot carry authorizations");
        expect(NullPointerException.class,
                () -> AutonomyPolicy.decide(null, ActionKind.OBSERVE, "x"), "null grant");
        expect(NullPointerException.class,
                () -> AutonomyPolicy.decide(AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT), null, "x"),
                "null action kind");
        check(AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT).autoAuthorizedActions().isEmpty(),
                "ordinary grants are empty");
    }

    private static AutonomyPolicy.Decision decide(AutonomyGrant grant, ActionKind kind) {
        return decide(grant, kind, "action");
    }

    private static AutonomyPolicy.Decision decide(AutonomyGrant grant, ActionKind kind, String actionId) {
        return AutonomyPolicy.decide(grant, kind, actionId);
    }
}
