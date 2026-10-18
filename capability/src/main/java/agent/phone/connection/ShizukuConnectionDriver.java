package agent.phone.connection;

import android.content.Context;

/** Android/Shizuku-backed driver used by the app process. */
public final class ShizukuConnectionDriver implements ConnectionDriver {

    private final ShizukuConnection connection;

    public ShizukuConnectionDriver(Context context, Listener listener) {
        if (context == null || listener == null) {
            throw new NullPointerException("context and listener are required");
        }
        connection = new ShizukuConnection(context, listener::onStateChanged);
    }

    @Override
    public void connect() {
        connection.connect();
    }

    @Override
    public void createDisplay() {
        connection.createDisplay();
    }

    @Override
    public void checkDisplay() {
        connection.checkDisplay();
    }

    @Override
    public void releaseDisplay() {
        connection.releaseDisplay();
    }

    @Override
    public void bindTarget(String taskId, String[] allowedPackages, String selected) {
        connection.bindTarget(taskId, allowedPackages, selected);
    }

    @Override
    public void releaseTarget() {
        connection.releaseTarget();
    }

    @Override
    public void launchTarget() {
        connection.launchTarget();
    }

    @Override
    public void observeTarget() {
        connection.observeTarget();
    }

    @Override
    public void captureTarget() {
        connection.captureTarget();
    }

    @Override
    public void tap(int x, int y) {
        connection.tap(x, y);
    }

    @Override
    public void setText(String ref, String text) {
        connection.setText(ref, text);
    }

    @Override
    public void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        connection.swipe(startX, startY, endX, endY, durationMs);
    }

    @Override
    public void back() {
        connection.back();
    }


    public void moveTargetToDisplay(int displayId, java.util.function.Consumer<Boolean> callback) {
        connection.moveTargetToDisplay(displayId, callback);
    }


    public boolean startPreviewMirror(android.view.Surface surface) {
        return connection.startPreviewMirror(surface);
    }


    public void stopPreviewMirror() {
        connection.stopPreviewMirror();
    }


    public boolean userTap(int x, int y) {
        return connection.userTap(x, y);
    }


    public boolean userSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        return connection.userSwipe(x1, y1, x2, y2, durationMs);
    }

    @Override
    public void disconnect() {
        connection.disconnect();
    }

    @Override
    public void close() {
        connection.close();
    }
}
