package agent.next.brain.plan;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validated input to a planner; the model may only choose from installed/allowed apps. */
public record PlanningRequest(
        String taskId,
        String goal,
        List<String> constraints,
        Set<String> allowedApps,
        List<InstalledApp> installedApps,
        String primaryPackage,
        Set<String> deniedActions,
        Set<String> allowedVerifiers,
        AutonomyGrant autonomy) {

    public PlanningRequest {
        taskId = text(taskId, "taskId");
        goal = text(goal, "goal");
        Objects.requireNonNull(constraints, "constraints");
        constraints = List.copyOf(constraints);
        for (String constraint : constraints) {
            text(constraint, "constraint");
        }
        Objects.requireNonNull(allowedApps, "allowedApps");
        allowedApps = Set.copyOf(allowedApps);
        if (allowedApps.isEmpty()) {
            throw new IllegalArgumentException("allowedApps must not be empty");
        }
        Objects.requireNonNull(installedApps, "installedApps");
        installedApps = List.copyOf(installedApps);
        if (installedApps.isEmpty()) {
            throw new IllegalArgumentException("installedApps must not be empty");
        }
        primaryPackage = text(primaryPackage, "primaryPackage");
        if (!allowedApps.contains(primaryPackage)) {
            throw new IllegalArgumentException("primary package must be allowed");
        }
        for (InstalledApp app : installedApps) {
            if (!allowedApps.contains(app.packageName())) {
                throw new IllegalArgumentException("installed app is not allowed: "
                        + app.packageName());
            }
        }
        Objects.requireNonNull(deniedActions, "deniedActions");
        deniedActions = Set.copyOf(deniedActions);
        Objects.requireNonNull(allowedVerifiers, "allowedVerifiers");
        allowedVerifiers = Set.copyOf(allowedVerifiers);
        if (allowedVerifiers.isEmpty()) {
            throw new IllegalArgumentException("allowedVerifiers must not be empty");
        }
        autonomy = Objects.requireNonNull(autonomy, "autonomy");
        if (autonomy.level() == AutonomyLevel.A4_AUTHORIZED_AUTO) {
            throw new IllegalArgumentException("A4 is disabled in v1");
        }
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
