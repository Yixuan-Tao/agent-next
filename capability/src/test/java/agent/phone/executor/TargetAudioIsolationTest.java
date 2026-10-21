package agent.phone.executor;

import java.util.ArrayList;
import java.util.List;

public final class TargetAudioIsolationTest {
    public static void main(String[] args) throws Exception {
        Backend backend = new Backend();
        TargetAudioIsolation audio = new TargetAudioIsolation(backend);
        audio.bind(10234);
        check(backend.events.toString().equals("[mute:10234]"), "default muted before playback");
        audio.preview(false);
        check(backend.events.size() == 1, "read-only monitoring remains muted");
        audio.preview(true);
        check(backend.events.toString().equals("[mute:10234, release]"), "takeover restores normal routing");
        audio.preview(false);
        check(backend.events.size() == 3, "return to agent re-establishes mute");
        audio.mainScreen(true);
        audio.preview(true);
        audio.preview(false);
        check(backend.events.size() == 4, "main-screen takeover remains audible after mirror stops");
        audio.mainScreen(false);
        check(backend.events.size() == 5, "return to private display restores mute");
        audio.close();
        audio.close();
        check(backend.events.size() == 6, "close releases exactly once");

        Backend failing = new Backend();
        TargetAudioIsolation guarded = new TargetAudioIsolation(failing);
        failing.failOpen = true;
        expectFailure(() -> guarded.bind(10234));
        failing.failOpen = false;
        guarded.bind(10234);
        failing.failClose = true;
        expectFailure(() -> guarded.preview(true));
        expectFailure(guarded::close);
        check(failing.events.size() == 1, "uncertain cleanup retains resource ownership");
        failing.failClose = false;
        guarded.close();
        check(failing.events.size() == 2, "cleanup can be retried");

        Backend returning = new Backend();
        TargetAudioIsolation returnGuard = new TargetAudioIsolation(returning);
        returnGuard.bind(10234);
        returnGuard.preview(true);
        returning.failOpen = true;
        expectFailure(() -> returnGuard.preview(false));
        returning.failOpen = false;
        returnGuard.preview(false);
        check(returning.events.size() == 3, "failed re-mute does not falsely commit agent ownership");
        returnGuard.close();

        Backend same = new Backend();
        TargetAudioIsolation idempotent = new TargetAudioIsolation(same);
        idempotent.bind(10234);
        idempotent.bind(10234);
        check(same.events.toString().equals("[mute:10234]"), "rebinding the same uid keeps one mute");

        Backend mainFailure = new Backend();
        TargetAudioIsolation mainGuard = new TargetAudioIsolation(mainFailure);
        mainGuard.bind(10234);
        mainFailure.failClose = true;
        expectFailure(() -> mainGuard.mainScreen(true));
        mainFailure.failClose = false;
        mainGuard.mainScreen(true);
        check(mainFailure.events.toString().equals("[mute:10234, release]"),
                "main-screen failure keeps mute ownership and can be retried");
        mainGuard.close();

        Backend closeRetry = new Backend();
        TargetAudioIsolation retryGuard = new TargetAudioIsolation(closeRetry);
        retryGuard.bind(10234);
        closeRetry.failClose = true;
        expectFailure(retryGuard::close);
        check(closeRetry.closeCalls == 2, "backend close is attempted after a failed lease close");
        closeRetry.failClose = false;
        retryGuard.close();
        check(closeRetry.closeCalls == 3, "backend close is retried and confirms cleanup");
        retryGuard.bind(10234);
        check(closeRetry.events.get(closeRetry.events.size() - 1).equals("mute:10234"),
                "close retry resets ownership so the next bind mutes again");
        System.out.println("TargetAudioIsolationTest: PASS (default mute, read-only, takeover, return, "
                + "idempotent bind, main failure, cleanup retry)");
    }

    private static final class Backend implements TargetAudioIsolation.Backend {
        final List<String> events = new ArrayList<String>();
        boolean failOpen, failClose;
        int closeCalls;
        @Override public TargetAudioIsolation.Lease mute(int uid) throws Exception {
            if (failOpen) throw new Exception("unavailable");
            events.add("mute:" + uid);
            return () -> {
                if (failClose) throw new Exception("cleanup uncertain");
                events.add("release");
            };
        }
        @Override public void close() throws Exception {
            closeCalls++;
            if (failClose) throw new Exception("backend cleanup uncertain");
        }
    }

    private interface Checked { void run() throws Exception; }
    private static void expectFailure(Checked action) throws Exception {
        try { action.run(); } catch (Exception expected) { return; }
        throw new AssertionError("expected a failure");
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
