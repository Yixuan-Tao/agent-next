package agent.next.brain;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A mutable-graph node. Dependencies are ids of other subgoals; required
 * conditions are semantic and will be bound to evidence by a verifier.
 *
 * <p>{@code targetPackage} selects the app for this subgoal. Empty means the
 * session's current target. {@code verifierId} selects a domain/visual verifier;
 * empty means the generic visual verifier.</p>
 */
public record Subgoal(
        String id,
        String intent,
        Set<String> dependsOn,
        List<Condition> preconditions,
        List<Condition> postconditions,
        Fallback fallback,
        RiskClass risk,
        boolean required,
        String targetPackage,
        String verifierId) {

    private static final String PACKAGE_PATTERN =
            "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+";
    private static final String VERIFIER_PATTERN = "[a-z][a-z0-9_.-]{0,63}";

    public Subgoal(String id, String intent, Set<String> dependsOn,
            List<Condition> preconditions, List<Condition> postconditions,
            Fallback fallback, RiskClass risk, boolean required) {
        this(id, intent, dependsOn, preconditions, postconditions, fallback, risk,
                required, "", "");
    }

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
        targetPackage = targetPackage == null ? "" : targetPackage.trim();
        if (!targetPackage.isEmpty() && !targetPackage.matches(PACKAGE_PATTERN)) {
            throw new IllegalArgumentException("invalid target package: " + targetPackage);
        }
        verifierId = verifierId == null ? "" : verifierId.trim();
        if (!verifierId.isEmpty() && !verifierId.matches(VERIFIER_PATTERN)) {
            throw new IllegalArgumentException("invalid verifier id: " + verifierId);
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
