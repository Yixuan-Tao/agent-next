package agent.next.brain.session;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.model.StageData;
import agent.next.device.DeviceException;
import agent.next.device.Observation;

/** App-owned bridge to visual StageData extraction; may capture a frame internally. */
@FunctionalInterface
public interface SessionStageExtractor {

    StageData extract(TaskSpec task, Subgoal subgoal, Observation observation)
            throws DeviceException;
}
