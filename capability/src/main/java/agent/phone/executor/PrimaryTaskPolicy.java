package agent.phone.executor;

/** A root's topActivity cannot identify every visible child in a split root. */
public final class PrimaryTaskPolicy {
    private PrimaryTaskPolicy() { }
    public static boolean isTarget(int displayId,boolean visible,int[] childTaskIds,String topPackage,String targetPackage) {
        if (displayId != 0) throw new IllegalStateException("Primary task display changed");
        if (!visible) return false;
        if (childTaskIds == null || childTaskIds.length != 1 || childTaskIds[0] < 0 || topPackage == null) {
            throw new IllegalStateException("Primary visible task identity is ambiguous");
        }
        return targetPackage.equals(topPackage);
    }
}
