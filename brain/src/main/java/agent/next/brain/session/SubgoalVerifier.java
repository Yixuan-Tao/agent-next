package agent.next.brain.session;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.device.DeviceException;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

import java.util.Objects;

/** Evidence-based verifier selected by {@link Subgoal#verifierId()}. */
public interface SubgoalVerifier {

    Verification verify(TaskSpec task, Subgoal subgoal, TargetHandle target,
            Observation observation)
            throws DeviceException;

    record Verification(boolean satisfied, String evidence) {
        public Verification {
            evidence = Objects.requireNonNull(evidence, "evidence");
        }
    }
}
