package agent.next.app.skill;

import java.util.Set;

/** Declarative per-app vocabulary and recognition prompts. */
public record AppProfile(
        String packageName,
        Set<String> searchEntryLabels,
        Set<String> searchEntryForbidden,
        Set<String> searchSubmitLabels,
        Set<String> searchSubmitForbidden,
        Set<String> resultCardLabels,
        Set<String> resultCardForbidden,
        Set<String> addLabels,
        Set<String> confirmLabels,
        Set<String> cartLabels,
        Set<String> cartForbidden,
        Set<String> detailMarkers,
        String cartVerifierId,
        String searchEntryPrompt,
        String searchSubmitPrompt,
        String resultCardPrompt) {

    public AppProfile {
        if (packageName == null || packageName.isBlank()) {
            throw new IllegalArgumentException("packageName required");
        }
        searchEntryLabels = Set.copyOf(searchEntryLabels);
        searchEntryForbidden = Set.copyOf(searchEntryForbidden);
        searchSubmitLabels = Set.copyOf(searchSubmitLabels);
        searchSubmitForbidden = Set.copyOf(searchSubmitForbidden);
        resultCardLabels = Set.copyOf(resultCardLabels);
        resultCardForbidden = Set.copyOf(resultCardForbidden);
        addLabels = Set.copyOf(addLabels);
        confirmLabels = Set.copyOf(confirmLabels);
        cartLabels = Set.copyOf(cartLabels);
        cartForbidden = Set.copyOf(cartForbidden);
        detailMarkers = Set.copyOf(detailMarkers);
        cartVerifierId = cartVerifierId == null ? "" : cartVerifierId;
        searchEntryPrompt = requirePrompt(searchEntryPrompt);
        searchSubmitPrompt = requirePrompt(searchSubmitPrompt);
        resultCardPrompt = requirePrompt(resultCardPrompt);
    }

    private static String requirePrompt(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("prompt required");
        }
        return value;
    }
}
