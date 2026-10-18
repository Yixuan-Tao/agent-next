package agent.next.brain.skill;

import agent.next.device.Observation;

import java.util.Map;
import java.util.Objects;

/** Evidence-based success check, independent of the workflow path. */
@FunctionalInterface
public interface SkillVerifier {

    Result verify(Observation observation, Map<String, String> parameters);

    record Result(boolean satisfied, String evidence) {
        public Result {
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }
}
