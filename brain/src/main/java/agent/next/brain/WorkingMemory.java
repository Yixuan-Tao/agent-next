package agent.next.brain;

import java.util.Map;
import java.util.Optional;

/** Task-scoped scratch state; not long-term memory. */
public interface WorkingMemory {

    void put(String key, String value);

    Optional<String> get(String key);

    Map<String, String> snapshot();

    void clear();
}
