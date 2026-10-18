package agent.next.brain.model;

import agent.next.device.Frame;

import java.util.List;
import java.util.Objects;

/** Request to extract a short stage summary and entities from one frame. */
public record StageExtractRequest(String task, Frame frame, List<String> visibleText) {

    public StageExtractRequest {
        Objects.requireNonNull(task, "task");
        if (task.isBlank() || task.length() > 4000) {
            throw new IllegalArgumentException("task is blank or too long");
        }
        frame = Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(visibleText, "visibleText");
        visibleText = List.copyOf(visibleText);
        if (visibleText.size() > 80) {
            throw new IllegalArgumentException("visibleText exceeds 80 lines");
        }
        for (String line : visibleText) {
            if (line == null || line.length() > 200) {
                throw new IllegalArgumentException("visible text line invalid");
            }
        }
    }
}
