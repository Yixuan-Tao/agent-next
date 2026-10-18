package agent.next.device;

/** Service-issued target identity scoped to one task and one private display. */
public final class TargetHandle {

    private final String token;
    private final long generation;
    private final String taskId;
    private final String packageName;

    public TargetHandle(String token, long generation, String taskId, String packageName) {
        this.token = Text.require(token, "token");
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        this.generation = generation;
        this.taskId = Text.requireTaskId(taskId);
        this.packageName = Text.requirePackage(packageName, "packageName");
    }

    public String token() {
        return token;
    }

    public long generation() {
        return generation;
    }

    public String taskId() {
        return taskId;
    }

    public String packageName() {
        return packageName;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof TargetHandle)) {
            return false;
        }
        TargetHandle other = (TargetHandle) value;
        return generation == other.generation
                && token.equals(other.token)
                && taskId.equals(other.taskId)
                && packageName.equals(other.packageName);
    }

    @Override
    public int hashCode() {
        int result = token.hashCode();
        result = 31 * result + (int) (generation ^ (generation >>> 32));
        result = 31 * result + taskId.hashCode();
        result = 31 * result + packageName.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "TargetHandle{" + token + ", task=" + taskId + ", package=" + packageName + '}';
    }
}
