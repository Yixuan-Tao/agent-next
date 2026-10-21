package agent.next.brain.reactive;

import agent.next.brain.TaskSpec;
import agent.next.device.Frame;
import agent.next.device.Observation;
import java.util.List;
import java.util.Objects;

/** Read-only current-page evidence plus bounded task-local execution history. */
public record ReactiveRequest(TaskSpec task, Observation observation, Frame frame,
        List<String> history, int turn, List<String> executionState) {
    public ReactiveRequest(TaskSpec task, Observation observation, Frame frame, List<String> history, int turn) {
        this(task, observation, frame, history, turn, List.of());
    }
    public ReactiveRequest {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(observation, "observation");
        history = List.copyOf(history);
        executionState = List.copyOf(executionState);
        if (turn < 0) throw new IllegalArgumentException("negative turn");
    }
}
