package agent.phone.executor;

/** Audio ownership follows the target, not whether somebody is watching its mirror. */
public final class TargetAudioIsolation {
    public interface Lease { void close() throws Exception; }
    public interface Backend {
        Lease mute(int uid) throws Exception;
        default void close() throws Exception { }
    }
    private final Backend backend;
    private Lease lease;
    private int uid = -1;
    private boolean preview, main;

    public TargetAudioIsolation(Backend backend) { this.backend = backend; }

    public synchronized void bind(int nextUid) throws Exception {
        if (nextUid < 10000) throw new IllegalArgumentException("Target must be an application UID");
        if (uid == nextUid) return;
        close();
        Lease next = backend.mute(nextUid);
        if (next == null) throw new IllegalStateException("Audio isolation was not established");
        lease = next;
        uid = nextUid;
    }

    public synchronized void preview(boolean userControl) throws Exception {
        update(userControl, main);
    }

    public synchronized void mainScreen(boolean userControl) throws Exception {
        update(preview, userControl);
    }

    private void update(boolean nextPreview, boolean nextMain) throws Exception {
        if (uid >= 0) {
            boolean mute = !nextPreview && !nextMain;
            if (mute && lease == null) {
                Lease next = backend.mute(uid);
                if (next == null) throw new IllegalStateException("Audio isolation was not established");
                lease = next;
            } else if (!mute && lease != null) {
                lease.close();
                lease = null;
            }
        }
        preview = nextPreview;
        main = nextMain;
    }

    public synchronized void close() throws Exception {
        Exception failure = null;
        if (lease != null) {
            try {
                lease.close();
                lease = null;
            } catch (Exception leaseFailure) {
                failure = leaseFailure;
            }
        }
        boolean backendClosed = false;
        try {
            backend.close();
            backendClosed = true;
        } catch (Exception backendFailure) {
            if (failure == null) failure = backendFailure;
            else failure.addSuppressed(backendFailure);
        }
        if (backendClosed) {
            lease = null;
            uid = -1;
            preview = false;
            main = false;
        }
        if (failure != null) throw failure;
    }
}
