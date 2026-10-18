package agent.phone.executor;

final class InstalledAppIdentity {
    private static final String PACKAGE_NAME = "agent.phone";

    private InstalledAppIdentity() { }

    static int resolveUid() {
        try {
            // UserService is not an app Context. Query the system package manager for user 0.
            // API 36 source: frameworks/base/core/java/android/content/pm/IPackageManager.aidl.
            Object packageManager = Class.forName("android.app.AppGlobals")
                    .getMethod("getPackageManager").invoke(null);
            int uid = (Integer) Class.forName("android.content.pm.IPackageManager")
                    .getMethod("getPackageUid", String.class, long.class, int.class)
                    .invoke(packageManager, PACKAGE_NAME, 0L, 0);
            return ClientPolicy.isOwnerAppUid(uid) ? uid : -1;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return -1;
        }
    }
}
