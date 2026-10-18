package agent.next.device;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Task-scoped target authorization. The selected package must be in the allowed set. */
public final class TargetSpec {

    private final String taskId;
    private final String packageName;
    private final Set<String> allowedPackages;

    public TargetSpec(String taskId, String packageName, Set<String> allowedPackages) {
        this.taskId = Text.requireTaskId(taskId);
        this.packageName = Text.requirePackage(packageName, "packageName");
        if (allowedPackages == null) {
            throw new NullPointerException("allowedPackages");
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String allowed : allowedPackages) {
            copy.add(Text.requirePackage(allowed, "allowed package"));
        }
        if (copy.isEmpty()) {
            throw new IllegalArgumentException("allowedPackages must not be empty");
        }
        if (!copy.contains(this.packageName)) {
            throw new IllegalArgumentException("selected package is not in allowedPackages");
        }
        this.allowedPackages = Collections.unmodifiableSet(copy);
    }

    public static TargetSpec single(String taskId, String packageName) {
        return new TargetSpec(taskId, packageName, Collections.singleton(packageName));
    }

    public String taskId() {
        return taskId;
    }

    public String packageName() {
        return packageName;
    }

    public Set<String> allowedPackages() {
        return allowedPackages;
    }

    @Override
    public String toString() {
        return "TargetSpec{" + taskId + ", " + packageName + ", allowed=" + allowedPackages + '}';
    }
}
