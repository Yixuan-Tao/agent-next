package agent.phone.connection;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** Activity listener ownership over one process-scoped, bounded connection coordinator. */
public final class ShizukuConnection implements AutoCloseable {
    public interface Listener { void onStateChanged(ConnectionState state); }

    private static ConnectionCoordinator processCoordinator;
    private static ShizukuConnection processOwner;
    private static final ThreadPoolExecutor PREVIEW_RPC = lane("agent-preview-rpc");
    private final PreviewRpc previewRpc = new PreviewRpc(PREVIEW_RPC, 10_000L);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectionCoordinator coordinator;
    private long owner;
    private final Shizuku.OnBinderReceivedListener received;
    private final Shizuku.OnBinderDeadListener dead;
    private final Shizuku.OnRequestPermissionResultListener permission;
    private volatile boolean closed;

    public ShizukuConnection(Context context, Listener listener) {
        synchronized (ShizukuConnection.class) {
            if (processOwner != null) {
                throw new IllegalStateException("another device session is active in this process");
            }
            if (processCoordinator == null) {
                processCoordinator = createCoordinator(context.getApplicationContext());
            }
            coordinator = processCoordinator;
            processOwner = this;
        }
        received = this::check;
        dead = () -> submit(coordinator::managerDied);
        permission = (code, result) -> submit(() ->
                coordinator.permission(code, result == PackageManager.PERMISSION_GRANTED));
        try {
            onMain(() -> {
                owner = coordinator.attach(listener::onStateChanged);
                Shizuku.addBinderDeadListener(dead, main);
                Shizuku.addRequestPermissionResultListener(permission, main);
                Shizuku.addBinderReceivedListenerSticky(received, main);
                coordinator.checkIfDisconnected();
                return null;
            });
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private static ConnectionCoordinator createCoordinator(Context context) {
        Handler handler = new Handler(Looper.getMainLooper());
        return new ConnectionCoordinator(new ShizukuBackend(context), new ConnectionCoordinator.Dispatcher() {
            @Override public void post(Runnable action) { handler.post(action); }
            @Override public ConnectionCoordinator.Cancellation after(long delay, Runnable action) {
                handler.postDelayed(action, delay);
                return () -> handler.removeCallbacks(action);
            }
        }, lane("agent-rpc"), lane("agent-cleanup"));
    }

    private static ThreadPoolExecutor lane(String name) {
        // These two process-lifetime lanes never grow when a synchronous Binder call cannot be interrupted.
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1),
                runnable -> new Thread(runnable, name), new ThreadPoolExecutor.AbortPolicy());
    }

    private void submit(Runnable action) {
        main.post(() -> { if (!closed) action.run(); });
    }

    private <T> T onMain(Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else if (!main.post(task)) {
            throw new IllegalStateException("device dispatcher is unavailable");
        }
        try {
            return task.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            task.cancel(false);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("device dispatcher interrupted", interrupted);
        } catch (Exception failure) {
            task.cancel(false);
            throw new IllegalStateException("device dispatcher failed", failure);
        }
    }

    public void check() { submit(coordinator::checkIfDisconnected); }
    public void connect() { submit(coordinator::connect); }
    public void createDisplay() { submit(coordinator::createDisplay); }
    public void checkDisplay() { submit(coordinator::checkDisplay); }
    public void releaseDisplay() { submit(coordinator::releaseDisplay); }
    public void bindTarget(String taskId, String[] allowedPackages, String selected) {
        submit(() -> coordinator.bindTarget(taskId,allowedPackages,selected));
    }
    public void releaseTarget() { submit(coordinator::releaseTarget); }
    public void launchTarget() { submit(coordinator::launchTarget); }
    public void observeTarget() { submit(coordinator::observeTarget); }
    public void captureTarget() { submit(coordinator::captureTarget); }
    public void tap(int x, int y) { submit(() -> coordinator.tap(x,y)); }
    public void setText(String ref, String text) { submit(() -> coordinator.setText(ref,text)); }
    public void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        submit(() -> coordinator.swipe(startX,startY,endX,endY,durationMs));
    }
    public void back() { submit(coordinator::back); }
    private boolean previewCall(Callable<Callable<Boolean>> prepare) {
        if (closed || Looper.myLooper() == Looper.getMainLooper()) return false;
        try {
            return previewRpc.call(onMain(prepare), null);
        } catch (RuntimeException failure) {
            return false;
        }
    }

    public boolean userTap(int x,int y) {
        return previewCall(() -> coordinator.prepareUserTap(x,y));
    }
    public boolean userSwipe(int x1,int y1,int x2,int y2,int durationMs) {
        return previewCall(() -> coordinator.prepareUserSwipe(x1,y1,x2,y2,durationMs));
    }
    public boolean startPreviewMirror(android.view.Surface surface, int channel) {
        if (closed || Looper.myLooper() == Looper.getMainLooper()) return false;
        try {
            ConnectionCoordinator.MirrorCalls calls = onMain(() -> coordinator.prepareMirror(surface,channel));
            return previewRpc.call(calls.start, calls.stop);
        } catch (RuntimeException failure) {
            return false;
        }
    }
    public boolean stopPreviewMirror(int channel) {
        if (closed || Looper.myLooper() == Looper.getMainLooper()) return false;
        try { return previewRpc.stop(onMain(() -> coordinator.prepareStopPreviewMirror(channel))); }
        catch (RuntimeException failure) { return false; }
    }
    public void moveTargetToDisplay(int displayId, java.util.function.Consumer<Boolean> callback) {
        submit(() -> coordinator.moveTargetToDisplay(displayId, callback));
    }
    public void disconnect() { submit(coordinator::disconnect); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try {
            onMain(() -> {
                Shizuku.removeBinderReceivedListener(received);
                Shizuku.removeBinderDeadListener(dead);
                Shizuku.removeRequestPermissionResultListener(permission);
                coordinator.detach(owner);
                return null;
            });
        } finally {
            synchronized (ShizukuConnection.class) {
                if (processOwner == this) processOwner = null;
            }
        }
    }
}
