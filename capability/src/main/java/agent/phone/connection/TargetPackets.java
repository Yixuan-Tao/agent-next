package agent.phone.connection;

import android.os.Bundle;
import java.util.Arrays;
import java.util.HashSet;

/** A binding reply is accepted only for the task, display and package requested by the App. */
public final class TargetPackets {
    private TargetPackets() { }
    public static Bundle encode(TargetSession t) {
        Bundle b = new Bundle(); DisplaySession d = t.display;
        b.putString("session_token",d.token); b.putLong("generation",d.generation);
        b.putInt("display_id",d.displayId); b.putString("unique_id",d.uniqueId);
        b.putInt("owner_uid",d.ownerUid); b.putInt("width",d.width); b.putInt("height",d.height);
        b.putInt("density_dpi",d.densityDpi); b.putInt("flags",d.flags);
        b.putString("task_id",t.taskId); b.putString("target_token",t.token); b.putLong("target_generation",t.generation);
        b.putString("package_name",t.packageName); b.putInt("target_uid",t.uid); b.putString("launcher_class",t.launcherClass);
        ObservationPackets.requireSize(b); return b;
    }
    public static TargetSession decode(Bundle b,DisplaySession expected,String taskId,String packageName) {
        ObservationPackets.requireSize(b);
        keys(b,"session_token","generation","display_id","unique_id","owner_uid","width","height","density_dpi",
                "flags","task_id","target_token","target_generation","package_name","target_uid","launcher_class");
        DisplaySession display = new DisplaySession(string(b,"session_token"),number(b,"generation"),integer(b,"display_id"),
                string(b,"unique_id"),integer(b,"owner_uid"),integer(b,"width"),integer(b,"height"),integer(b,"density_dpi"),
                integer(b,"flags"),DisplaySession.Status.ACTIVE);
        TargetSession target = new TargetSession(display,string(b,"task_id"),string(b,"target_token"),number(b,"target_generation"),
                string(b,"package_name"),integer(b,"target_uid"),string(b,"launcher_class"));
        if (expected == null || expected.status != DisplaySession.Status.ACTIVE || !display.sameIdentity(expected) || display.flags != expected.flags
                || !target.taskId.equals(taskId) || !target.packageName.equals(packageName)) throw malformed();
        return target;
    }
    static void keys(Bundle b,String... expected) {
        if (!b.keySet().equals(new HashSet<>(Arrays.asList(expected)))) throw malformed();
    }
    static String string(Bundle b,String key) { Object v = b.get(key); if (!(v instanceof String)) throw malformed(); return (String) v; }
    static int integer(Bundle b,String key) { Object v = b.get(key); if (!(v instanceof Integer)) throw malformed(); return (Integer) v; }
    static long number(Bundle b,String key) { Object v = b.get(key); if (!(v instanceof Long)) throw malformed(); return (Long) v; }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed target session packet"); }
}
