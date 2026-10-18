package agent.next.brain.model;

import agent.next.device.Frame;

import java.util.List;
import java.util.Objects;

/** One independent visual verification request; no device control is included. */
public record VisualVerificationRequest(String task, List<String> conditions, Frame frame) {

    public VisualVerificationRequest {
        task = require(task, "task", 4000);
        Objects.requireNonNull(conditions, "conditions");
        conditions = List.copyOf(conditions);
        if (conditions.isEmpty() || conditions.size() > 8) {
            throw new IllegalArgumentException("conditions must be 1..8");
        }
        for (String condition : conditions) {
            require(condition, "condition", 200);
        }
        frame = Objects.requireNonNull(frame, "frame");
    }

    private static String require(String value, String name, int max) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException(name + " is blank or too long");
        }
        return value;
    }
}
