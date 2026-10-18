package agent.next.brain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Small deterministic working-memory implementation for host tests and early loops. */
public final class InMemoryWorkingMemory implements WorkingMemory {

    private final Map<String, String> values = new LinkedHashMap<>();

    @Override
    public void put(String key, String value) {
        Preconditions.requireText(key, "key");
        Objects.requireNonNull(value, "value");
        values.put(key, value);
    }

    @Override
    public Optional<String> get(String key) {
        Objects.requireNonNull(key, "key");
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public Map<String, String> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    @Override
    public void clear() {
        values.clear();
    }
}
