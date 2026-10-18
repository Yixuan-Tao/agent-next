package agent.next.brain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A mutable-graph node. Dependencies are ids of other subgoals; required
 * conditions are semantic and will be bound to evidence by a verifier.
 */
public record Subgoal(
        String id,
        String intent,
        Set<String> dependsOn,
        List<Condition> preconditions,
        List<Condition> postconditions,
        Fallback fallback,
        RiskClass risk,
        boolean required) {

    public Subgoal {
        id = Preconditions.requireText(id, "id");
        intent = Preconditions.requireText(intent, "intent");
        Objects.requireNonNull(dependsOn, "dependsOn");
        dependsOn = Set.copyOf(dependsOn);
        for (String dependency : dependsOn) {
            Preconditions.requireText(dependency, "dependency");
        }

        Objects.requireNonNull(preconditions, "preconditions");
        preconditions = List.copyOf(preconditions);
        Objects.requireNonNull(postconditions, "postconditions");
        postconditions = List.copyOf(postconditions);
        requireUniqueConditionIds(preconditions, postconditions);

        fallback = Objects.requireNonNull(fallback, "fallback");
        risk = Objects.requireNonNull(risk, "risk");
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
