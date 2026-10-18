package agent.next.device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * One read-only observation. A non-READY observation carries no nodes and no
 * reference; callers must re-observe instead of acting on stale evidence.
 */
public final class Observation {

    private final ObservationStatus status;
    private final ObservationRef reference;
    private final String packageName;
    private final String activityName;
    private final int displayId;
    private final int windowId;
    private final boolean stable;
    private final Instant capturedAt;
    private final int lookupCount;
    private final boolean truncated;
    private final NodeBounds windowBounds;
    private final List<UiNode> nodes;

    public Observation(ObservationStatus status, ObservationRef reference,
            String packageName, String activityName, int displayId, int windowId,
            boolean stable, Instant capturedAt, int lookupCount, boolean truncated,
            NodeBounds windowBounds, List<UiNode> nodes) {
        if (status == null) {
            throw new NullPointerException("status");
        }
        this.packageName = Text.require(packageName, "packageName");
        if (activityName == null) {
            throw new NullPointerException("activityName");
        }
        if (displayId < 0) {
            throw new IllegalArgumentException("displayId must not be negative");
        }
        if (capturedAt == null) {
            throw new NullPointerException("capturedAt");
        }
        if (lookupCount < 0) {
            throw new IllegalArgumentException("lookupCount must not be negative");
        }
        if (nodes == null) {
            throw new NullPointerException("nodes");
        }
        List<UiNode> copy = new ArrayList<>();
        copy.addAll(nodes);
        if (status == ObservationStatus.READY) {
            if (reference == null) {
                throw new IllegalArgumentException("ready observation requires a reference");
            }
            if (windowId < 0) {
                throw new IllegalArgumentException("ready observation requires a window id");
            }
            if (windowBounds == null) {
                throw new IllegalArgumentException("ready observation requires window bounds");
            }
        } else {
            if (reference != null) {
                throw new IllegalArgumentException("non-ready observation cannot be actionable");
            }
            if (windowId != -1 || windowBounds != null || !copy.isEmpty()) {
                throw new IllegalArgumentException("non-ready observation must be empty");
            }
        }
        for (UiNode node : copy) {
            if (node == null) {
                throw new NullPointerException("node");
            }
        }
        this.status = status;
        this.reference = reference;
        this.activityName = activityName;
        this.displayId = displayId;
        this.windowId = windowId;
        this.stable = stable;
        this.capturedAt = capturedAt;
        this.lookupCount = lookupCount;
        this.truncated = truncated;
        this.windowBounds = windowBounds;
        this.nodes = Collections.unmodifiableList(copy);
    }

    public ObservationStatus status() {
        return status;
    }

    public Optional<ObservationRef> reference() {
        return Optional.ofNullable(reference);
    }

    public String packageName() {
        return packageName;
    }

    public String activityName() {
        return activityName;
    }

    public int displayId() {
        return displayId;
    }

    public int windowId() {
        return windowId;
    }

    public boolean stable() {
        return stable;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public int lookupCount() {
        return lookupCount;
    }

    public boolean truncated() {
        return truncated;
    }

    public Optional<NodeBounds> windowBounds() {
        return Optional.ofNullable(windowBounds);
    }

    public List<UiNode> nodes() {
        return nodes;
    }

    public Optional<UiNode> node(String ref) {
        for (UiNode node : nodes) {
            if (node.ref().equals(ref)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "Observation{" + status + ", " + packageName + "/" + activityName
                + ", nodes=" + nodes.size() + '}';
    }
}
