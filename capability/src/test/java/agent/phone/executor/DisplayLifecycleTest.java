package agent.phone.executor;

public final class DisplayLifecycleTest {
    public static void main(String[] args) throws Exception {
        repeatedCreateAndStaleTokens();
        invalidMetadataAndStickyInvalidation();
        partialFailureAndUnconfirmedCleanup();
        closeIsTerminalAndIdempotent();
        System.out.println("DisplayLifecycleTest: PASS (single resource, identity, failure, cleanup)");
    }
    private static void repeatedCreateAndStaleTokens() throws Exception {
        Factory f = new Factory(); DisplayLifecycle lifecycle = new DisplayLifecycle(f);
        DisplayLifecycle.Session first = lifecycle.create();
        check(first == lifecycle.create() && f.created == 1, "duplicate create reuses verified resource");
        reject(() -> lifecycle.release("wrong", first.generation));
        reject(() -> lifecycle.check(first.token, first.generation + 1));
        check(f.resource.closes == 0, "bad credentials cannot release resource");
        check(lifecycle.release(first.token, first.generation).status == DisplayLifecycle.Status.RELEASED, "release confirmed");
        lifecycle.release(first.token, first.generation);
        check(f.resource.closes == 1, "same release is idempotent");
        DisplayLifecycle.Session second = lifecycle.create();
        check(second.generation > first.generation && !second.token.equals(first.token), "new server identity");
        reject(() -> lifecycle.release(first.token, first.generation));
        check(lifecycle.check(second.token, second.generation).status == DisplayLifecycle.Status.ACTIVE,
                "old token cannot affect newly reused display ID");
    }
    private static void invalidMetadataAndStickyInvalidation() throws Exception {
        for (int fault = 0; fault < 7; fault++) {
            Factory f = new Factory(); DisplayLifecycle lifecycle = new DisplayLifecycle(f);
            DisplayLifecycle.Session session = lifecycle.create();
            f.resource.info = info(fault);
            check(lifecycle.check(session.token, session.generation).status == DisplayLifecycle.Status.INVALID,
                    "metadata or disappearance invalidates session " + fault);
            f.resource.info = info(-1);
            reject(lifecycle::create);
            check(lifecycle.check(session.token, session.generation).status == DisplayLifecycle.Status.INVALID,
                    "invalidated identity cannot revive after display ID reuse");
            lifecycle.release(session.token, session.generation);
        }
        Factory f = new Factory(); f.nextInfo = info(0);
        reject(() -> new DisplayLifecycle(f).create());
        check(f.resource.closes == 1, "invalid initial display metadata triggers cleanup");
    }
    private static void partialFailureAndUnconfirmedCleanup() throws Exception {
        Factory f = new Factory(); f.failOpen = true;
        DisplayLifecycle lifecycle = new DisplayLifecycle(f);
        reject(lifecycle::create);
        check(f.resource.closes == 1, "partial open failure releases allocated resources");
        f.failOpen = false;
        DisplayLifecycle.Session session = lifecycle.create();
        f.resource.failClose = true;
        check(lifecycle.release(session.token, session.generation).status == DisplayLifecycle.Status.CLEANUP_FAILED,
                "failed cleanup is not released");
        reject(lifecycle::create);
        f.resource.failClose = false;
        check(lifecycle.release(session.token, session.generation).status == DisplayLifecycle.Status.RELEASED,
                "explicit retry can confirm release");
        Factory partial = new Factory(); partial.failOpen = true; partial.failClose = true;
        DisplayLifecycle failed = new DisplayLifecycle(partial); reject(failed::create);
        partial.failOpen = false;
        reject(failed::create);
        check(partial.created == 1, "unknown partial cleanup blocks replacement allocation");
        partial.resource.failClose = false; failed.close();
    }
    private static void closeIsTerminalAndIdempotent() throws Exception {
        Factory f = new Factory(); DisplayLifecycle lifecycle = new DisplayLifecycle(f);
        lifecycle.create(); lifecycle.close(); lifecycle.close();
        check(f.resource.closes == 1, "destroy closes resources once");
        reject(lifecycle::create);
    }
    private static DisplayLifecycle.Info info(int fault) {
        return fault == 6 ? null : new DisplayLifecycle.Info(fault == 0 ? 0 : 7,
                fault == 1 ? "reused" : "virtual:test", fault == 2 ? 1000 : 2000,
                fault == 3 ? 1 : 1080, 1920, 360, fault == 4 ? 0 : 42,
                fault == 5 ? "other" : "agent-phone");
    }
    private static final class Factory implements DisplayLifecycle.Factory {
        int created; boolean failOpen; boolean failClose; DisplayLifecycle.Info nextInfo = info(-1); Resource resource;
        @Override public DisplayLifecycle.Resource allocate() {
            created++; resource = new Resource(); resource.info = nextInfo;
            resource.failOpen = failOpen; resource.failClose = failClose; return resource;
        }
        @Override public int expectedFlags() { return 42; }
    }
    private static final class Resource implements DisplayLifecycle.Resource {
        DisplayLifecycle.Info info; boolean failOpen; boolean failClose; int closes;
        @Override public void open() { if (failOpen) throw new IllegalStateException("controlled partial failure"); }
        @Override public DisplayLifecycle.Info inspect() { return info; }
        @Override public void close() { closes++; if (failClose) throw new IllegalStateException("controlled release failure"); }
    }
    private interface Call { Object run() throws Exception; }
    private static void reject(Call call) throws Exception {
        try { call.run(); } catch (IllegalStateException | SecurityException expected) { return; }
        throw new AssertionError("unsafe operation accepted");
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
