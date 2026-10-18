package agent.phone.connection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Host-side driver fake. Lives in the connection package so it can construct
 * the package-private {@link ConnectionState} and {@link Capabilities}.
 */
public final class FakeConnectionDriver implements ConnectionDriver {

    private Listener listener;
    private boolean closed;
    private DisplaySession display;
    private TargetSession target;
    private TargetObservation observation;
    private FrameEvidence.Capture frame;
    private long observationSequence;
    private long frameSequence;

    public void attach(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void connect() {
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void createDisplay() {
        display = new DisplaySession("display-1", 1, 101, "fake:display:101",
                2000, 720, 1280, 240, 0, DisplaySession.Status.ACTIVE);
        target = null;
        observation = null;
        frame = null;
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void checkDisplay() {
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void releaseDisplay() {
        DisplaySession released = new DisplaySession(display.token, display.generation,
                display.displayId, display.uniqueId, display.ownerUid, display.width,
                display.height, display.densityDpi, display.flags,
                DisplaySession.Status.RELEASED);
        display = released;
        target = null;
        observation = null;
        frame = null;
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void bindTarget(String taskId, String[] allowedPackages, String selected) {
        target = new TargetSession(display, taskId, "target-1", 1, selected, 10274,
                "com.example.shop.MainActivity");
        observation = null;
        frame = null;
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void releaseTarget() {
        target = null;
        observation = null;
        frame = null;
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void launchTarget() {
        observation = observation();
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void observeTarget() {
        observation = observation();
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void captureTarget() {
        frame = frame();
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void tap(int x, int y) {
        consumeObservation();
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void setText(String ref, String text) {
        consumeObservation();
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void swipe(int startX, int startY, int endX, int endY, int durationMs) {
        consumeObservation();
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void back() {
        consumeObservation();
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void moveTargetToDisplay(int displayId, Consumer<Boolean> callback) {
        emit(ConnectionState.Status.CONNECTED, ConnectionState.Reason.AVAILABLE,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
        callback.accept(Boolean.TRUE);
    }

    @Override
    public void disconnect() {
        display = null;
        target = null;
        observation = null;
        frame = null;
        emit(ConnectionState.Status.DISCONNECTED, ConnectionState.Reason.USER,
                ConnectionState.DisplayOperation.NONE, ConnectionState.TargetOperation.NONE);
    }

    @Override
    public void close() {
        closed = true;
    }

    private void consumeObservation() {
        observation = null;
        frame = null;
    }

    private void emit(ConnectionState.Status status, ConnectionState.Reason reason,
            ConnectionState.DisplayOperation displayOperation,
            ConnectionState.TargetOperation targetOperation) {
        if (listener == null || closed) {
            return;
        }
        listener.onStateChanged(new ConnectionState(status, reason,
                new Capabilities(11, 36, 2000, 10000, status == ConnectionState.Status.CONNECTED, true),
                true, false, display, displayOperation, target, observation, frame,
                targetOperation));
    }

    private TargetObservation observation() {
        String id = "obs-" + (++observationSequence);
        TargetObservation.Bounds bounds = new TargetObservation.Bounds(0, 0, 720, 1280);
        List<TargetObservation.Node> nodes = new ArrayList<>();
        nodes.add(new TargetObservation.Node(id + ":0", 0, 1000, -1, bounds,
                "", "", "android:id/root", "android.widget.FrameLayout",
                false, false, true, false, true, false, false, false));
        nodes.add(new TargetObservation.Node(id + ":1", 1, 1000, 0,
                new TargetObservation.Bounds(20, 100, 700, 160),
                "", "Search", "com.example.shop:id/search", "android.widget.EditText",
                false, true, true, false, true, true, false, false));
        nodes.add(new TargetObservation.Node(id + ":2", 2, 1000, 0,
                new TargetObservation.Bounds(20, 200, 700, 260),
                "Search", "", "com.example.shop:id/submit", "android.widget.Button",
                true, false, true, false, true, false, false, false));
        nodes.add(new TargetObservation.Node(id + ":3", 3, 1000, 0,
                new TargetObservation.Bounds(20, 300, 700, 360),
                "", "", "com.example.shop:id/password", "android.widget.EditText",
                false, true, true, true, true, false, false, false));
        long now = System.nanoTime();
        return new TargetObservation(target, id, observationSequence, now, now,
                TargetObservation.Status.READY, 1000, bounds, nodes.size(), false, nodes);
    }

    private FrameEvidence.Capture frame() {
        byte[] png = png();
        long now = System.nanoTime();
        FrameEvidence evidence = new FrameEvidence(target, "frame-" + (++frameSequence),
                frameSequence, now, now, observation == null ? "obs-1" : observation.observationId,
                1000, 720, 1280, png.length);
        return new FrameEvidence.Capture(evidence, png);
    }

    private static byte[] png() {
        byte[] png = new byte[33];
        int[] signature = {137, 80, 78, 71, 13, 10, 26, 10};
        for (int i = 0; i < signature.length; i++) {
            png[i] = (byte) signature[i];
        }
        putInt(png, 8, 13);
        png[12] = 'I';
        png[13] = 'H';
        png[14] = 'D';
        png[15] = 'R';
        putInt(png, 16, 720);
        putInt(png, 20, 1280);
        png[24] = 8;
        png[25] = 2;
        return png;
    }

    private static void putInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }
}
