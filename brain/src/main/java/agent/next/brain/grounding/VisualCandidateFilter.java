package agent.next.brain.grounding;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Safety-first candidate selection: allow-list, forbid-list, confidence and uniqueness. */
public final class VisualCandidateFilter {

    private VisualCandidateFilter() {
    }

    public static Optional<VisualCandidate> select(List<VisualCandidate> candidates,
            Set<String> allowedTokens, Set<String> forbiddenTokens, double minConfidence) {
        if (candidates == null || candidates.isEmpty() || minConfidence < 0
                || minConfidence > 1) {
            return Optional.empty();
        }
        VisualCandidate best = null;
        for (VisualCandidate candidate : candidates) {
            String label = candidate.label().toLowerCase();
            if (candidate.confidence() < minConfidence) {
                continue;
            }
            if (!forbiddenTokens.isEmpty()
                    && forbiddenTokens.stream().anyMatch(label::contains)) {
                continue;
            }
            if (!allowedTokens.isEmpty()
                    && allowedTokens.stream().noneMatch(label::contains)) {
                continue;
            }
            if (best == null || candidate.confidence() > best.confidence()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }
}
