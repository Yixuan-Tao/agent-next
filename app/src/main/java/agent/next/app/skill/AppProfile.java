package agent.next.app.skill;

import java.util.Set;

/** Declarative per-app vocabulary. Core policies interpret this; no app code in core. */
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
        String cartVerifierId) {

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
    }
}
