package agent.next.brain;

import java.util.Map;
import java.util.Objects;

/** One executable step inside a {@link Skill}. Parameters are semantic, not coordinates. */
public record SkillStep(String actionId, String description, Map<String, String> parameters) {

    public SkillStep {
        actionId = Preconditions.requireText(actionId, "actionId");
        description = Preconditions.requireText(description, "description");
        Objects.requireNonNull(parameters, "parameters");
        parameters = Map.copyOf(parameters);
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            Preconditions.requireText(entry.getKey(), "parameter name");
            Objects.requireNonNull(entry.getValue(), "parameter value");
        }
    }
}
