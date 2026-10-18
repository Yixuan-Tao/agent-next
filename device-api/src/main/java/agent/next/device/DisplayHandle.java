package agent.next.device;

/** Service-issued display identity; the numeric display id alone is never authorization. */
public final class DisplayHandle {

    private final String token;
    private final long generation;

    public DisplayHandle(String token, long generation) {
        this.token = Text.require(token, "token");
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        this.generation = generation;
    }

    public String token() {
        return token;
    }

    public long generation() {
        return generation;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof DisplayHandle)) {
            return false;
        }
        DisplayHandle other = (DisplayHandle) value;
        return generation == other.generation && token.equals(other.token);
    }

    @Override
    public int hashCode() {
        return 31 * token.hashCode() + (int) (generation ^ (generation >>> 32));
    }

    @Override
    public String toString() {
        return "DisplayHandle{" + token + ", generation=" + generation + '}';
    }
}
