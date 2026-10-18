package agent.next.brain;

import java.time.Duration;
import java.time.Instant;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

final class ConfirmationTest {

    private ConfirmationTest() {
    }

    static void run() {
        validWindow();
        bindingAndExpiry();
        validation();
        System.out.println("ConfirmationTest: PASS (payload binding, expiry, rejection)");
    }

    private static void validWindow() {
        Instant now = Instant.parse("2026-10-19T00:00:00Z");
        ConfirmationRequest request = request("order-1", "payload-1");
        ConfirmationToken token = request.confirm(now, Duration.ofMinutes(2));

        check(token.matches(request), "token matches request");
        check(token.isValidAt(now), "valid immediately");
        check(token.isValidAt(now.plusSeconds(119)), "valid before expiry");
        check(!token.isValidAt(now.plusSeconds(120)), "invalid at expiry");
        token.requireValidFor(request, now.plusSeconds(30));
    }

    private static void bindingAndExpiry() {
        Instant now = Instant.parse("2026-10-19T00:00:00Z");
        ConfirmationRequest request = request("order-1", "payload-1");
        ConfirmationToken token = request.confirm(now, Duration.ofMinutes(2));

        expect(ConfirmationRejectedException.class,
                () -> token.requireValidFor(request("order-1", "payload-2"), now.plusSeconds(1)),
                "changed payload invalidates");
        expect(ConfirmationRejectedException.class,
                () -> token.requireValidFor(request("order-2", "payload-1"), now.plusSeconds(1)),
                "changed action invalidates");
        expect(ConfirmationRejectedException.class,
                () -> token.requireValidFor(request, now.plusSeconds(120)),
                "expired token rejected");
        expect(ConfirmationRejectedException.class,
                () -> token.requireValidFor(request, now.minusSeconds(1)),
                "token is not valid before issue time");
        check(!token.matches(request("order-1", "payload-2")), "matches is false for changed payload");
    }

    private static void validation() {
        Instant now = Instant.parse("2026-10-19T00:00:00Z");
        ConfirmationRequest request = request("order-1", "payload-1");

        expect(IllegalArgumentException.class,
                () -> request.confirm(now, Duration.ZERO), "zero validity");
        expect(IllegalArgumentException.class,
                () -> request.confirm(now, Duration.ofSeconds(-1)), "negative validity");
        expect(IllegalArgumentException.class,
                () -> new ConfirmationToken("order-1", "payload-1", now, now), "empty token window");
        expect(IllegalArgumentException.class,
                () -> new ConfirmationRequest(" ", "summary", "consequences", false, "hash"),
                "blank action id");
        expect(NullPointerException.class,
                () -> request.confirm(null, Duration.ofMinutes(1)), "null confirmation time");
        expect(NullPointerException.class,
                () -> request.confirm(now, null), "null validity");
    }

    private static ConfirmationRequest request(String actionId, String payloadHash) {
        return new ConfirmationRequest(
                actionId, "Place order", "Charges 99 CNY; irreversible", false, payloadHash);
    }
}
