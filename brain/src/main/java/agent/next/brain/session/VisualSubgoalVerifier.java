package agent.next.brain.session;

import agent.next.brain.Condition;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.model.VisualVerification;
import agent.next.brain.model.VisualVerificationRequest;
import agent.next.brain.model.VisualVerifier;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Independent visual verifier adapter. Conditions come from the subgoal
 * postconditions (fallback: task success criteria); completion requires the
 * image model to see every condition.
 */
public final class VisualSubgoalVerifier implements SubgoalVerifier {

    private final VisualVerifier visual;
    private final TargetFrameSource frames;

    public VisualSubgoalVerifier(VisualVerifier visual, TargetFrameSource frames) {
        this.visual = Objects.requireNonNull(visual, "visual");
        this.frames = Objects.requireNonNull(frames, "frames");
    }

    @Override
    public Verification verify(TaskSpec task, Subgoal subgoal, TargetHandle target,
            Observation observation) throws DeviceException {
        List<String> conditions = new ArrayList<>();
        for (Condition condition : subgoal.postconditions()) {
            conditions.add(condition.description());
        }
        if (conditions.isEmpty()) {
            for (SuccessCriterion criterion : task.successCriteria()) {
                conditions.add(criterion.description());
            }
        }
        if (conditions.isEmpty() || conditions.size() > 8) {
            return new Verification(false, "no bounded verification conditions");
        }
        Frame frame = frames.capture(target);
        if (frame == null) {
            return new Verification(false, "no frame available");
        }
        try {
            VisualVerification result = visual.verify(new VisualVerificationRequest(
                    subgoal.intent(), conditions, frame));
            String evidence = "matched=" + result.matched().size()
                    + ", missing=" + result.missing().size();
            return new Verification(result.verified(), evidence);
        } catch (IOException failure) {
            throw new DeviceException(DeviceException.Code.INTERNAL,
                    "visual verification failed", failure);
        }
    }
}
