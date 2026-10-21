package agent.phone.connection;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keeps uncertain mirror calls exclusive until late completion and cleanup settle. */
final class PreviewRpc {
    private final ThreadPoolExecutor executor;
    private final long timeoutMillis;
    private Pending active;
    private boolean unknownMirror;

    PreviewRpc(ThreadPoolExecutor executor, long timeoutMillis) {
        this.executor = executor;
        this.timeoutMillis = timeoutMillis;
    }

    boolean call(Callable<Boolean> action, Callable<Boolean> compensation) {
        return execute(action, compensation, false);
    }

    boolean stop(Callable<Boolean> action) { return execute(action, null, true); }

    private boolean execute(Callable<Boolean> action, Callable<Boolean> compensation, boolean recovery) {
        Pending next = new Pending(action, compensation, recovery);
        synchronized (this) {
            if (active != null || unknownMirror && !recovery) return false;
            active = next;
        }
        try {
            executor.execute(next);
        } catch (RuntimeException rejected) {
            synchronized (this) { if (active == next) active = null; }
            return false;
        }
        try {
            next.finished.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        synchronized (this) {
            if (next.done) return next.accepted;
            next.abandoned = true;
            // A queued call has no remote effect. A running call must finish its own cleanup.
            if (executor.remove(next) && active == next) active = null;
            return false;
        }
    }

    private static boolean invoke(Callable<Boolean> action) {
        try { return Boolean.TRUE.equals(action.call()); }
        catch (Exception failure) { return false; }
    }

    private final class Pending implements Runnable {
        final Callable<Boolean> action;
        final Callable<Boolean> compensation;
        final boolean recovery;
        final CountDownLatch finished = new CountDownLatch(1);
        boolean abandoned;
        boolean done;
        boolean accepted;

        Pending(Callable<Boolean> action, Callable<Boolean> compensation, boolean recovery) {
            this.action = action;
            this.compensation = compensation;
            this.recovery = recovery;
        }

        @Override public void run() {
            synchronized (PreviewRpc.this) {
                if (abandoned) { finish(false); return; }
            }
            boolean succeeded = invoke(action);
            synchronized (PreviewRpc.this) {
                if (compensation == null || succeeded && !abandoned) {
                    if (recovery) unknownMirror = !succeeded;
                    finish(succeeded && !abandoned);
                    return;
                }
            }
            boolean cleaned = invoke(compensation);
            synchronized (PreviewRpc.this) {
                unknownMirror = !cleaned;
                finish(false);
            }
        }

        private void finish(boolean result) {
            accepted = result;
            done = true;
            if (active == this) active = null;
            finished.countDown();
        }
    }
}
