package agent.next.brain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The validated task envelope: goal, constraints, success criteria, autonomy,
 * app scope, task-level denials, and hard budget.
 *
 * <p>A4 is intentionally rejected in v1 (decision D1). It remains represented
 * by {@link AutonomyLevel} and {@link AutonomyGrant} for future work.</p>
 */
public record TaskSpec(
        String id,
        String goal,
        List<String> constraints,
        List<SuccessCriterion> successCriteria,
        AutonomyGrant autonomy,
        Set<String> allowedApps,
        Set<String> deniedActions,
        Budget budget,
        boolean handoffOnVerifiedCompletion) {

    /** Backward-compatible constructor for tasks that complete without a handoff. */
    public TaskSpec(String id, String goal, List<String> constraints,
            List<SuccessCriterion> successCriteria, AutonomyGrant autonomy,
            Set<String> allowedApps, Set<String> deniedActions, Budget budget) {
        this(id, goal, constraints, successCriteria, autonomy, allowedApps,
                deniedActions, budget, false);
    }

    public TaskSpec {
        id = Preconditions.requireText(id, "id");
        goal = Preconditions.requireText(goal, "goal");
        Objects.requireNonNull(constraints, "constraints");
        constraints = List.copyOf(constraints);
        for (String constraint : constraints) {
            Preconditions.requireText(constraint, "constraint");
        }

        Objects.requireNonNull(successCriteria, "successCriteria");
        successCriteria = List.copyOf(successCriteria);
        if (successCriteria.isEmpty()) {
            throw new IllegalArgumentException("successCriteria must not be empty");
        }
        if (successCriteria.stream().noneMatch(SuccessCriterion::required)) {
            throw new IllegalArgumentException("at least one success criterion must be required");
        }

        autonomy = Objects.requireNonNull(autonomy, "autonomy");
        if (autonomy.level() == AutonomyLevel.A4_AUTHORIZED_AUTO) {
            throw new IllegalArgumentException("A4 is disabled in v1; use A3 or lower");
        }

        Objects.requireNonNull(allowedApps, "allowedApps");
        allowedApps = Set.copyOf(allowedApps);
        if (allowedApps.isEmpty()) {
            throw new IllegalArgumentException("allowedApps must not be empty");
        }
        for (String app : allowedApps) {
            Preconditions.requireText(app, "allowed app");
        }

        Objects.requireNonNull(deniedActions, "deniedActions");
        deniedActions = Set.copyOf(deniedActions);
        for (String action : deniedActions) {
            Preconditions.requireText(action, "denied action");
        }

        budget = Objects.requireNonNull(budget, "budget");
    }

    public boolean denies(String actionId) {
        return actionId != null && deniedActions.contains(actionId);
    }
}
