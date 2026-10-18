package agent.next.device;

/** Current state of a target; displayId 0 means the main screen (user takeover). */
public final class TargetInfo {

    private final String taskId;
    private final String packageName;
    private final String activityName;
    private final int uid;
    private final int displayId;
    private final boolean foreground;
    private final long generation;

    public TargetInfo(String taskId, String packageName, String activityName, int uid,
            int displayId, boolean foreground, long generation) {
        this.taskId = Text.requireTaskId(taskId);
        this.packageName = Text.requirePackage(packageName, "packageName");
        this.activityName = Text.require(activityName, "activityName");
        if (uid < 0) {
            throw new IllegalArgumentException("uid must not be negative");
        }
        if (displayId < 0) {
            throw new IllegalArgumentException("displayId must not be negative");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        this.uid = uid;
        this.displayId = displayId;
        this.foreground = foreground;
        this.generation = generation;
    }

    public String taskId() {
        return taskId;
    }

    public String packageName() {
        return packageName;
    }

    public String activityName() {
        return activityName;
    }

    public int uid() {
        return uid;
    }

    public int displayId() {
        return displayId;
    }

    public boolean foreground() {
        return foreground;
    }

    public long generation() {
        return generation;
    }

    @Override
    public String toString() {
        return "TargetInfo{" + packageName + ", display=" + displayId
                + ", foreground=" + foreground + '}';
    }
}
