package agent.qualification;

/** Shared bound for every root or child lookup, including the final identity check. */
final class ObservationBudget {
    static final int MAX_LOOKUPS = 1000;
    private int reserved;
    int recorded;
    boolean truncated;

    boolean reserve() {
        if (reserved >= MAX_LOOKUPS) {
            truncated = true;
            return false;
        }
        reserved++;
        return true;
    }

    void requireLookup() {
        if (!reserve()) {
            throw new IllegalStateException("node lookup budget exhausted before window identity validation");
        }
    }

    int lookups() {
        return reserved;
    }
}
