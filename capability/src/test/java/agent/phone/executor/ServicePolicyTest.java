package agent.phone.executor;

public final class ServicePolicyTest {
    private ServicePolicyTest() { }

    public static void main(String[] args) {
        currentInstalledAppIsTheOnlyDataCaller();
        managerMayOnlyCloseTheService();
        protocolMustMatchExactly();
        supportIsLimitedToTheVerifiedEnvironment();
        capabilitiesCannotSubstituteAnotherAppIdentity();
        System.out.println("ServicePolicyTest: PASS (caller identity, close authority, protocol, support boundary)");
    }

    private static void currentInstalledAppIsTheOnlyDataCaller() {
        ClientPolicy.requireAppCaller(10234, 10234);
        for (int caller : new int[] {-1, 0, 2000, 1000, 10233, 10235, 110234, 99000}) {
            denied(() -> ClientPolicy.requireAppCaller(caller, 10234));
        }
        for (int invalidExpected : new int[] {-1, 0, 2000, 9999, 20000, 100000, 110234}) {
            denied(() -> ClientPolicy.requireAppCaller(invalidExpected, invalidExpected));
        }
        // A caller cannot enroll itself by presenting a plausible application UID.
        denied(() -> ClientPolicy.requireAppCaller(10567, 10234));
    }

    private static void managerMayOnlyCloseTheService() {
        ClientPolicy.requireCloseCaller(10234, 10234);
        ClientPolicy.requireCloseCaller(2000, 10234);
        ClientPolicy.requireCloseCaller(0, 10234);
        // Cleanup remains available to the manager even if installation lookup failed.
        ClientPolicy.requireCloseCaller(2000, -1);
        for (int caller : new int[] {-1, 1000, 10235, 110234}) {
            denied(() -> ClientPolicy.requireCloseCaller(caller, 10234));
        }
        denied(() -> ClientPolicy.requireCloseCaller(-1, -1));
    }

    private static void protocolMustMatchExactly() {
        ProtocolPolicy.requireProtocol(ProtocolPolicy.VERSION);
        check("authorized_target_frame".equals(ProtocolPolicy.SCOPE), "capability scope names the authorized target frame");
        for (int protocol : new int[] {-1, 0, 1, 2, Integer.MAX_VALUE}) {
            invalid(() -> ProtocolPolicy.requireProtocol(protocol));
        }
    }

    private static void supportIsLimitedToTheVerifiedEnvironment() {
        check(ProtocolPolicy.isSupported(36, 2000), "API 36 and shell are supported");
        for (int api : new int[] {-1, 0, 35, 37}) {
            check(!ProtocolPolicy.isSupported(api, 2000), "unknown APIs are not verified");
        }
        for (int uid : new int[] {-1, 0, 1000, 10000}) {
            check(!ProtocolPolicy.isSupported(36, uid), "root or app is not verified shell");
        }
        check(!ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, 36, 0, 10234, 10234, false),
                "root may be described but cannot be shown as supported");
        invalid(() -> ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, 36, 0, 10234, 10234, true));
        invalid(() -> ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, 36, 2000, 10234, 10234, false));
    }

    private static void capabilitiesCannotSubstituteAnotherAppIdentity() {
        check(ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, 36, 2000, 10234, 10234, true),
                "matching live handshake is supported");
        invalid(() -> ProtocolPolicy.validateCapabilities(1, 36, 2000, 10234, 10234, true));
        invalid(() -> ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, 36, 2000, 10235, 10234, true));
        invalid(() -> ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, 36, 2000, -1, -1, true));
        invalid(() -> ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, 36, 2000, 110234, 110234, true));
        invalid(() -> ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, -1, 2000, 10234, 10234, false));
        invalid(() -> ProtocolPolicy.validateCapabilities(ProtocolPolicy.VERSION, 36, -1, 10234, 10234, false));
    }

    private static void denied(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected SecurityException");
        } catch (SecurityException expected) { }
    }

    private static void invalid(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
