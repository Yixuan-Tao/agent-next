package agent.next.brain.plan;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;

import java.util.List;
import java.util.Objects;

/** A confirmed-candidate plan; execution is still gated by the app plan review. */
public record TaskPlan(TaskSpec task, List<Subgoal> subgoals, List<String> assumptions) {

    public TaskPlan {
        task = Objects.requireNonNull(task, "task");
        Objects.requireNonNull(subgoals, "subgoals");
        subgoals = List.copyOf(subgoals);
        if (subgoals.isEmpty() || subgoals.size() > 8) {
            throw new IllegalArgumentException("subgoals must be 1..8");
        }
        Objects.requireNonNull(assumptions, "assumptions");
        assumptions = List.copyOf(assumptions);
        for (String assumption : assumptions) {
            if (assumption == null || assumption.isBlank()) {
                throw new IllegalArgumentException("assumptions must not be blank");
            }
        }
        for (Subgoal subgoal : subgoals) {
            if (!subgoal.targetPackage().isEmpty()
                    && !task.allowedApps().contains(subgoal.targetPackage())) {
                throw new IllegalArgumentException("subgoal target is not allowed: "
                        + subgoal.targetPackage());
            }
        }
        // Reuse the graph validator for duplicate ids, unknown dependencies and cycles.
        new TaskGraph(subgoals);
    }
}
