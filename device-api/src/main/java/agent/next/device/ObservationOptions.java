package agent.next.device;

/** Observation limits; the implementation may impose tighter hard limits. */
public final class ObservationOptions {

    private final int maxNodes;
    private final int maxTextCodePoints;

    public ObservationOptions(int maxNodes, int maxTextCodePoints) {
        Text.requirePositive(maxNodes, "maxNodes");
        Text.requirePositive(maxTextCodePoints, "maxTextCodePoints");
        this.maxNodes = maxNodes;
        this.maxTextCodePoints = maxTextCodePoints;
    }

    public static ObservationOptions defaults() {
        return new ObservationOptions(1000, 512);
    }

    public int maxNodes() {
        return maxNodes;
    }

    public int maxTextCodePoints() {
        return maxTextCodePoints;
    }

    @Override
    public String toString() {
        return "ObservationOptions{maxNodes=" + maxNodes
                + ", maxTextCodePoints=" + maxTextCodePoints + '}';
    }
}
