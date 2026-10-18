package agent.next.device;

/** Identity of one observation; input requests must cite the current reference. */
public final class ObservationRef {

    private final String observationId;
    private final long sequence;

    public ObservationRef(String observationId, long sequence) {
        this.observationId = Text.require(observationId, "observationId");
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        this.sequence = sequence;
    }

    public String observationId() {
        return observationId;
    }

    public long sequence() {
        return sequence;
    }

    @Override
    public String toString() {
        return "ObservationRef{" + observationId + ", sequence=" + sequence + '}';
    }
}
