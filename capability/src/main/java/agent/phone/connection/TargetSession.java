package agent.phone.connection;

import java.util.HashSet;
import java.util.Set;

/** Service-issued target credentials scoped to one authorized task and owned display. */
public final class TargetSession {
    public final DisplaySession display;
    public final String taskId,token,packageName,launcherClass;
    public final long generation;
    public final int uid;
    public TargetSession(DisplaySession display,String taskId,String token,long generation,String packageName,int uid,String launcherClass) {
        requireTaskId(taskId);
        requirePackage(packageName);
        if (display == null || display.status != DisplaySession.Status.ACTIVE || token == null || token.isEmpty()
                || token.length() > 128 || generation <= 0 || uid < 10000 || uid >= 100000 || launcherClass == null
                || launcherClass.length() > 512 || !launcherClass.matches("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")) {
            throw new IllegalArgumentException("Malformed target session");
        }
        this.display = display; this.taskId = taskId; this.token = token; this.generation = generation;
        this.packageName = packageName; this.uid = uid; this.launcherClass = launcherClass;
    }
    public boolean sameIdentity(TargetSession other) {
        return other != null && token.equals(other.token) && generation == other.generation && taskId.equals(other.taskId)
                && packageName.equals(other.packageName) && uid == other.uid && launcherClass.equals(other.launcherClass)
                && display.sameIdentity(other.display) && display.flags == other.display.flags;
    }
    public static void requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("Invalid task identity");
        }
    }
    public static void requirePackage(String value) {
        if (value == null || value.length() > 255 || !value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("Invalid target package");
        }
    }
    public static void requireAuthorization(String taskId,String[] allowed,String selected) {
        requireTaskId(taskId); requirePackage(selected);
        if (allowed == null || allowed.length == 0 || allowed.length > 64) throw new IllegalArgumentException("Invalid allowed packages");
        Set<String> packages = new HashSet<>();
        for (String pkg : allowed) { requirePackage(pkg); if (!packages.add(pkg)) throw new IllegalArgumentException("Duplicate package"); }
        if (!packages.contains(selected)) throw new SecurityException("Target package is not authorized");
    }
}
