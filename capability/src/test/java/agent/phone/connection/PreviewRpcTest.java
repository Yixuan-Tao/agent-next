package agent.phone.connection;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PreviewRpcTest {
    private PreviewRpcTest() { }

    public static void main(String[] args) throws Exception {
        lateStartIsStoppedBeforeAnotherMirrorCanStart();
        failedCompensationRequiresAConfirmedStop();
        queuedCallThatTimesOutNeverStarts();
        System.out.println("PreviewRpcTest: PASS (late start compensation and uncertain ownership)");
    }

    private static ThreadPoolExecutor lane() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(1));
    }

    private static void lateStartIsStoppedBeforeAnotherMirrorCanStart() throws Exception {
        ThreadPoolExecutor executor = lane();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean remoteActive = new AtomicBoolean();
        AtomicBoolean replacementStarted = new AtomicBoolean();
        PreviewRpc rpc = new PreviewRpc(executor, 100);
        try {
            check(!rpc.call(() -> {
                entered.countDown();
                release.await();
                remoteActive.set(true);
                return true;
            }, () -> {
                remoteActive.set(false);
                stopped.countDown();
                return true;
            }), "blocked start must time out");
            check(entered.getCount() == 0, "the RPC must already be running at timeout");
            check(!rpc.call(() -> { replacementStarted.set(true); return true; }, null),
                    "a pending start must reject another mirror");
            release.countDown();
            check(stopped.await(1, TimeUnit.SECONDS), "late successful start must be compensated");
            executor.submit(() -> { }).get(1, TimeUnit.SECONDS);
            check(!remoteActive.get() && !replacementStarted.get(),
                    "no unowned or replacement mirror may survive the timeout");
            check(rpc.stop(() -> true), "confirmed stop permits reuse after compensation");
            check(rpc.call(() -> true, null), "preview lane remains reusable");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void failedCompensationRequiresAConfirmedStop() throws Exception {
        ThreadPoolExecutor executor = lane();
        PreviewRpc rpc = new PreviewRpc(executor, 100);
        try {
            check(!rpc.call(() -> false, () -> false), "failed start/stop must not succeed");
            check(!rpc.call(() -> true, null), "unknown ownership must block another mirror");
            check(!rpc.stop(() -> false), "unconfirmed stop must preserve unknown ownership");
            check(!rpc.call(() -> true, null), "failed stop must not grant another mirror");
            check(rpc.stop(() -> true), "confirmed stop resolves unknown ownership");
            check(rpc.call(() -> true, null), "confirmed cleanup allows a new mirror");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void queuedCallThatTimesOutNeverStarts() throws Exception {
        ThreadPoolExecutor executor = lane();
        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean dispatched = new AtomicBoolean();
        PreviewRpc rpc = new PreviewRpc(executor, 100);
        try {
            executor.execute(() -> {
                occupied.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            check(occupied.await(1, TimeUnit.SECONDS), "executor must be occupied");
            check(!rpc.call(() -> { dispatched.set(true); return true; }, null),
                    "a queued call must time out without an effect");
            release.countDown();
            executor.submit(() -> { }).get(1, TimeUnit.SECONDS);
            check(!dispatched.get(), "timed-out queued input must never be dispatched");
            check(rpc.call(() -> true, null), "removing a queued call permits safe reuse");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
