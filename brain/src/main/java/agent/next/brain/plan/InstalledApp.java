package agent.next.brain.plan;

import java.util.Objects;

/** Installed app surfaced to the planner; labels are user-visible but never instructions. */
public record InstalledApp(String packageName, String label) {

    public InstalledApp {
        if (packageName == null || !packageName.matches(
                "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("invalid package name");
        }
        Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > 120) {
            throw new IllegalArgumentException("invalid app label");
        }
    }
}
