package agent.next.brain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Structured episodic record. Raw frames and full UI trees are not stored here. */
public record TaskEvent(
        String taskId,
        Instant at,
        EventType type,
        String summary,
        Map<String, String> data) {

    public enum EventType {
        OBSERVATION,
        ACTION,
        EFFECT,
        CONFIRMATION,
        HANDOFF,
        FAILURE,
        COMPLETION
    }

    public TaskEvent {
        taskId = Preconditions.requireText(taskId, "taskId");
        at = Objects.requireNonNull(at, "at");
        type = Objects.requireNonNull(type, "type");
        summary = Preconditions.requireText(summary, "summary");
        Objects.requireNonNull(data, "data");
        data = Map.copyOf(data);
        for (Map.Entry<String, String> entry : data.entrySet()) {
            Preconditions.requireText(entry.getKey(), "data key");
            Objects.requireNonNull(entry.getValue(), "data value");
        }
    }
}
