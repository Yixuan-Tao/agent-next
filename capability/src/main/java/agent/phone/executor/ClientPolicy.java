package agent.phone.executor;

public final class ClientPolicy {
    private ClientPolicy() { }

    public static void requireAppCaller(int callingUid, int installedAppUid) {
        if (!isOwnerAppUid(installedAppUid) || callingUid != installedAppUid) {
            throw new SecurityException("Only the installed agent.phone app may call this service");
        }
    }

    public static void requireCloseCaller(int callingUid, int installedAppUid) {
        if (callingUid == 2000 || callingUid == 0) return;
        requireAppCaller(callingUid, installedAppUid);
    }

    public static boolean isOwnerAppUid(int uid) {
        return uid >= 10000 && uid <= 19999;
    }
}
