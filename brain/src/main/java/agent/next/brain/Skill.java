package agent.next.brain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A semantic capability with preconditions, steps, postconditions, fallback,
 * risk, and relative cost. It is not a fixed coordinate script.
 */
public record Skill(
        String id,
        String intent,
        List<Condition> preconditions,
        List<SkillStep> steps,
        List<Condition> postconditions,
        Fallback fallback,
        RiskClass risk,
        int cost) {

    public Skill {
        id = Preconditions.requireText(id, "id");
        intent = Preconditions.requireText(intent, "intent");

        Objects.requireNonNull(preconditions, "preconditions");
        preconditions = List.copyOf(preconditions);
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("skill must have at least one step");
        }
        Objects.requireNonNull(postconditions, "postconditions");
        postconditions = List.copyOf(postconditions);
        requireUniqueConditionIds(preconditions, postconditions);

        fallback = Objects.requireNonNull(fallback, "fallback");
        risk = Objects.requireNonNull(risk, "risk");
        if (cost < 0) {
            throw new IllegalArgumentException("cost must not be negative");
        }
    }

    private static void requireUniqueConditionIds(List<Condition> preconditions, List<Condition> postconditions) {
        Set<String> ids = new HashSet<>();
        for (Condition condition : preconditions) {
            if (!ids.add(condition.id())) {
                throw new IllegalArgumentException("duplicate condition id: " + condition.id());
            }
        }
        for (Condition condition : postconditions) {
            if (!ids.add(condition.id())) {
                throw new IllegalArgumentException("duplicate condition id: " + condition.id());
            }
        }
    }
}
