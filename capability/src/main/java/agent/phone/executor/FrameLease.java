package agent.phone.executor;

/** Keeps exactly one sender descriptor alive until a later RPC or confirmed cleanup. */
@SuppressWarnings("try")
public final class FrameLease<T extends AutoCloseable> implements AutoCloseable {
    private T current;
    public void replace(T next) throws Exception {
        if (next == null || next == current) throw new IllegalArgumentException("Invalid frame resource");
        try { close(); }
        catch (Exception failure) {
            try { next.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        current = next;
    }
    @Override public void close() throws Exception {
        if (current != null) { current.close(); current = null; }
    }
}
