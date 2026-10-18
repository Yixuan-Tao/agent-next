package agent.next.brain.plan;

import java.util.Objects;

/** Exactly one of a plan or a single clarification question. */
public record PlanningResult(TaskPlan plan, String clarification) {

    public PlanningResult {
        if ((plan == null) == (clarification == null)) {
            throw new IllegalArgumentException("exactly one of plan/clarification is required");
        }
        if (clarification != null && clarification.isBlank()) {
            throw new IllegalArgumentException("clarification must not be blank");
        }
    }

    public static PlanningResult plan(TaskPlan plan) {
        return new PlanningResult(Objects.requireNonNull(plan, "plan"), null);
    }

    public static PlanningResult clarify(String question) {
        return new PlanningResult(null, Objects.requireNonNull(question, "question"));
    }

    public boolean needsClarification() {
        return clarification != null;
    }
}
