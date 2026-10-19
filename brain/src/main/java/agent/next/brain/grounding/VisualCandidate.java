package agent.next.brain.grounding;

import java.util.Objects;

/** A normalized visual grounding candidate returned by a recognition model. */
public record VisualCandidate(String label, double x, double y, double confidence) {

    public VisualCandidate {
        label = Objects.requireNonNull(label, "label").trim();
        if (label.isEmpty() || label.length() > 80) {
            throw new IllegalArgumentException("invalid candidate label");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y)
                || x <= 0 || x >= 1 || y <= 0 || y >= 1) {
            throw new IllegalArgumentException("candidate point must be normalized (0,1)");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("candidate confidence must be 0..1");
        }
    }
}
