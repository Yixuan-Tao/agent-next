package agent.next.brain.model;

import java.util.List;

/** Result of an independent visual verification pass. */
public record VisualVerification(boolean verified, List<String> matched, List<String> missing) {

    public VisualVerification {
        matched = List.copyOf(matched);
        missing = List.copyOf(missing);
    }
}
