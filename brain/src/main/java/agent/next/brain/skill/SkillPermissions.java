package agent.next.brain.skill;

import java.util.Objects;
import java.util.Set;

/** Permissions a skill package must declare; the workflow policy refuses undeclared input. */
public record SkillPermissions(Set<SkillCapability> capabilities, Set<String> targetPackages) {

    public SkillPermissions {
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Set.copyOf(capabilities);
        Objects.requireNonNull(targetPackages, "targetPackages");
        targetPackages = Set.copyOf(targetPackages);
        if (targetPackages.isEmpty()) {
            throw new IllegalArgumentException("targetPackages must not be empty");
        }
        for (String target : targetPackages) {
            Checks.text(target, "target package");
        }
    }

    public boolean allows(SkillCapability capability) {
        return capabilities.contains(capability);
    }

    public boolean allowsTarget(String packageName) {
        return targetPackages.contains(packageName);
    }
}
