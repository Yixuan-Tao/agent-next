package agent.next.brain.contract;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.Budget;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable task envelope. It records what the user asked for before planning:
 * structured parameters, required outcomes, denied actions, allowed apps and
 * verifiers. Plans and skills may not weaken it.
 */
public record TaskContract(
        String taskId,
        String goal,
        Map<String, String> parameters,
        List<RequiredOutcome> requiredOutcomes,
        List<String> constraints,
        Set<String> allowedApps,
        String primaryPackage,
        Set<String> deniedActions,
        Set<String> allowedVerifiers,
        AutonomyGrant autonomy,
        Budget budget,
        int version) {

    public TaskContract {
        taskId = text(taskId, "taskId");
        goal = text(goal, "goal");
        Objects.requireNonNull(parameters, "parameters");
        parameters = Map.copyOf(parameters);
        if (parameters.size() > 32) {
            throw new IllegalArgumentException("too many parameters");
        }
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            text(entry.getKey(), "parameter key");
            text(entry.getValue(), "parameter value");
        }
        Objects.requireNonNull(requiredOutcomes, "requiredOutcomes");
        requiredOutcomes = List.copyOf(requiredOutcomes);
        if (requiredOutcomes.isEmpty() || requiredOutcomes.size() > 8) {
            throw new IllegalArgumentException("requiredOutcomes must be 1..8");
        }
        Set<String> ids = new java.util.HashSet<>();
        for (RequiredOutcome outcome : requiredOutcomes) {
            if (!ids.add(outcome.id())) {
                throw new IllegalArgumentException("duplicate required outcome: " + outcome.id());
            }
        }
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
        primaryPackage = text(primaryPackage, "primaryPackage");
        if (!allowedApps.contains(primaryPackage)) {
            throw new IllegalArgumentException("primary package must be allowed");
        }
        Objects.requireNonNull(deniedActions, "deniedActions");
        deniedActions = Set.copyOf(deniedActions);
        Objects.requireNonNull(allowedVerifiers, "allowedVerifiers");
        allowedVerifiers = Set.copyOf(allowedVerifiers);
        if (allowedVerifiers.isEmpty()) {
            throw new IllegalArgumentException("allowedVerifiers must not be empty");
        }
        autonomy = Objects.requireNonNull(autonomy, "autonomy");
        budget = Objects.requireNonNull(budget, "budget");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
    }

    public record RequiredOutcome(String id, String description, String verifierId) {
        public RequiredOutcome {
            id = text(id, "outcome id");
            description = text(description, "outcome description");
            verifierId = verifierId == null ? "" : verifierId;
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
