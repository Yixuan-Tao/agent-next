package agent.phone.executor;

public final class FrameLeaseTest {
    public static void main(String[] args) throws Exception {
        FrameLease<Resource> lease = new FrameLease<>();
        Resource first = new Resource(), second = new Resource();
        lease.replace(first); check(first.closes == 0,"marshal owner stays alive");
        lease.replace(second); check(first.closes == 1 && second.closes == 0,"only latest resource retained");
        lease.close(); lease.close(); check(second.closes == 1,"close is idempotent");
        Resource failing = new Resource(); failing.fail = true;
        lease.replace(failing);
        Resource rejected = new Resource();
        try { lease.replace(rejected); throw new AssertionError("expected cleanup failure"); }
        catch (IllegalStateException expected) { }
        check(rejected.closes == 1,"replacement closed when previous cleanup fails");
        failing.fail = false; lease.close(); check(failing.closes == 2,"uncertain prior owner retained for retry");
        System.out.println("FrameLeaseTest: PASS (one live frame, replacement, close, failed cleanup)");
    }
    static final class Resource implements AutoCloseable {
        int closes; boolean fail;
        @Override public void close() { closes++; if (fail) throw new IllegalStateException("close"); }
    }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
