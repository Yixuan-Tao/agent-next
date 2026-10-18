package agent.next.brain.skill;

import agent.next.device.Observation;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only request to a model-backed prompt skill. It carries no DeviceApi,
 * TargetHandle, or coordinate type; the model can only propose semantic steps.
 */
public record SkillModelRequest(
        String skillId,
        String prompt,
        Observation observation,
        String targetPackage,
        Map<String, String> parameters,
        Set<SkillCapability> capabilities,
        int step) {

    public SkillModelRequest {
        skillId = Checks.text(skillId, "skillId");
        prompt = Checks.text(prompt, "prompt");
        observation = Objects.requireNonNull(observation, "observation");
        targetPackage = Checks.text(targetPackage, "targetPackage");
        Objects.requireNonNull(parameters, "parameters");
        parameters = Map.copyOf(parameters);
        Objects.requireNonNull(capabilities, "capabilities");
        capabilities = Set.copyOf(capabilities);
        if (step < 0) {
            throw new IllegalArgumentException("step must not be negative");
        }
    }
}
