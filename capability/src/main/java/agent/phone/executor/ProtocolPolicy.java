package agent.phone.executor;

public final class ProtocolPolicy {
    public static final int VERSION = 3;
    public static final String SCOPE = "authorized_target_frame";
    public static final String KEY_PROTOCOL = "protocol_version";
    public static final String KEY_API = "android_api";
    public static final String KEY_SERVICE_UID = "service_uid";
    public static final String KEY_APP_UID = "app_uid";
    public static final String KEY_SUPPORTED = "supported";
    public static final String KEY_SCOPE = "scope";
    public static final String KEY_PREVIEW_MIRROR = "preview_mirror";

    private ProtocolPolicy() { }

    public static void requireProtocol(int protocol) {
        if (protocol != VERSION) {
            throw new IllegalArgumentException("Unsupported handshake protocol");
        }
    }

    public static boolean isSupported(int api, int serviceUid) {
        return api >= 33 && api <= 36 && serviceUid == 2000;
    }

    public static boolean validateCapabilities(int protocol, int api, int serviceUid,
            int appUid, int expectedAppUid, boolean supported) {
        requireProtocol(protocol);
        if (api <= 0 || serviceUid < 0 || !ClientPolicy.isOwnerAppUid(expectedAppUid) || appUid != expectedAppUid
                || supported != isSupported(api, serviceUid)) {
            throw new IllegalArgumentException("Invalid service capabilities");
        }
        return supported;
    }
}
