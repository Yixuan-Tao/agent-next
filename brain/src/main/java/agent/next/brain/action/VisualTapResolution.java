package agent.next.brain.action;

import agent.next.device.Point;
import java.util.Objects;

/** A visual target grounded in a fresh screenshot, including the label used for risk review. */
public record VisualTapResolution(Point point, String label, double confidence) {
    public VisualTapResolution {
        point = Objects.requireNonNull(point, "point");
        label = Objects.requireNonNull(label, "label");
        if (label.isBlank() || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("invalid visual tap resolution");
        }
    }
}
