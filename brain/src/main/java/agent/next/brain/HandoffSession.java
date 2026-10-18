package agent.next.brain;

import java.time.Instant;
import java.util.Objects;

/** A human takeover episode; ending it requires re-observation before resuming. */
public record HandoffSession(String id, HandoffRequest request, Instant startedAt) {

    public HandoffSession {
        id = Preconditions.requireText(id, "id");
        request = Objects.requireNonNull(request, "request");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }
}
