package agent.next.brain.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicit verifier allowlist; unknown ids fail closed. */
public final class VerifierRegistry {

    private final Map<String, SubgoalVerifier> verifiers = new LinkedHashMap<>();

    public VerifierRegistry register(String id, SubgoalVerifier verifier) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("verifier id must not be blank");
        }
        verifiers.put(id, Objects.requireNonNull(verifier, "verifier"));
        return this;
    }

    public Optional<SubgoalVerifier> find(String id) {
        return Optional.ofNullable(verifiers.get(id == null || id.isBlank() ? "visual" : id));
    }
}
