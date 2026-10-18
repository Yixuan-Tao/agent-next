package agent.phone.connection;

/**
 * Narrow adapter surface over {@link ShizukuConnection}. Kept separate so the
 * synchronous device-api adapter can be host-tested with a fake driver.
 */
public interface ConnectionDriver extends AutoCloseable {

    interface Listener {
        void onStateChanged(ConnectionState state);
    }

    void connect();

    void createDisplay();

    void checkDisplay();

    void releaseDisplay();

    void bindTarget(String taskId, String[] allowedPackages, String selected);

    void releaseTarget();

    void launchTarget();

    void observeTarget();

    void captureTarget();

    void tap(int x, int y);

    void setText(String ref, String text);

    void swipe(int startX, int startY, int endX, int endY, int durationMs);

    void back();

    void moveTargetToDisplay(int displayId, java.util.function.Consumer<Boolean> callback);

    void disconnect();

    @Override
    void close();
}
