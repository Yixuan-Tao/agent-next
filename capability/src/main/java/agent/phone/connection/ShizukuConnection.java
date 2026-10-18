package agent.phone.connection;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

/** Activity listener ownership over one process-scoped, bounded connection coordinator. */
public final class ShizukuConnection implements AutoCloseable {
    public interface Listener { void onStateChanged(ConnectionState state); }

    private static ConnectionCoordinator processCoordinator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectionCoordinator coordinator;
    private final long owner;
    private final Shizuku.OnBinderReceivedListener received;
    private final Shizuku.OnBinderDeadListener dead;
    private final Shizuku.OnRequestPermissionResultListener permission;
    private boolean closed;

    public ShizukuConnection(Context context, Listener listener) {
        synchronized (ShizukuConnection.class) {
            if (processCoordinator == null) {
                processCoordinator = createCoordinator(context.getApplicationContext());
            }
            coordinator = processCoordinator;
        }
        owner = coordinator.attach(listener::onStateChanged);
        received = () -> { if (!closed) coordinator.check(); };
        dead = () -> { if (!closed) coordinator.managerDied(); };
        permission = (code, result) -> {
            if (!closed) coordinator.permission(code, result == PackageManager.PERMISSION_GRANTED);
        };
        Shizuku.addBinderDeadListener(dead, main);
        Shizuku.addRequestPermissionResultListener(permission, main);
        Shizuku.addBinderReceivedListenerSticky(received, main);
        check();
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

    public void check() { if (!closed) coordinator.check(); }
    public void connect() { if (!closed) coordinator.connect(); }
    public void createDisplay() { if (!closed) coordinator.createDisplay(); }
    public void checkDisplay() { if (!closed) coordinator.checkDisplay(); }
    public void releaseDisplay() { if (!closed) coordinator.releaseDisplay(); }
    public void bindTarget(String taskId, String[] allowedPackages, String selected) {
        if (!closed) coordinator.bindTarget(taskId,allowedPackages,selected);
    }
    public void launchTarget() { if (!closed) coordinator.launchTarget(); }
    public void observeTarget() { if (!closed) coordinator.observeTarget(); }
    public void captureTarget() { if (!closed) coordinator.captureTarget(); }
    public void tap(int x, int y) { if (!closed) coordinator.tap(x,y); }
    public void setText(String ref, String text) { if (!closed) coordinator.setText(ref,text); }
    public void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        if (!closed) coordinator.swipe(startX,startY,endX,endY,durationMs);
    }
    public void back() { if (!closed) coordinator.back(); }
    public boolean userTap(int x,int y) { return !closed && coordinator.userTap(x,y); }
    public boolean userSwipe(int x1,int y1,int x2,int y2,int durationMs) {
        return !closed && coordinator.userSwipe(x1,y1,x2,y2,durationMs);
    }
    public boolean startPreviewMirror(android.view.Surface surface) {
        return !closed && coordinator.startPreviewMirror(surface);
    }
    public void stopPreviewMirror() { if (!closed) coordinator.stopPreviewMirror(); }
    public void moveTargetToDisplay(int displayId, java.util.function.Consumer<Boolean> callback) {
        if (!closed) coordinator.moveTargetToDisplay(displayId, callback);
    }
    public void disconnect() { if (!closed) coordinator.disconnect(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Shizuku.removeBinderReceivedListener(received);
        Shizuku.removeBinderDeadListener(dead);
        Shizuku.removeRequestPermissionResultListener(permission);
        coordinator.detach(owner);
    }
}
