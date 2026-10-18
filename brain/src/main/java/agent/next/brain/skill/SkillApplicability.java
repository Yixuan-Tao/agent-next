package agent.next.brain.skill;

import agent.next.device.Observation;

import java.util.Map;
import java.util.Objects;

/**
 * Applicability check run before a skill may act. It only sees an observation,
 * the current target package, and invocation parameters; it gets no device or
 * coordinate API.
 */
@FunctionalInterface
public interface SkillApplicability {

    Result check(Observation observation, String targetPackage, Map<String, String> parameters);

    record Result(boolean applicable, String reason) {
        public Result {
            reason = Objects.requireNonNull(reason, "reason");
        }
    }

    static SkillApplicability always() {
        return (observation, targetPackage, parameters) -> new Result(true, "always");
    }
}
