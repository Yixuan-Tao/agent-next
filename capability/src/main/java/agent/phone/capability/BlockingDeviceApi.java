package agent.phone.capability;

import agent.next.device.DeviceApi;
import agent.next.device.DeviceCapabilities;
import agent.next.device.DeviceException;
import agent.next.device.DeviceFeature;
import agent.next.device.DisplayController;
import agent.next.device.DisplayHandle;
import agent.next.device.DisplayInfo;
import agent.next.device.DisplaySpec;
import agent.next.device.Frame;
import agent.next.device.InputController;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.NodeBounds;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.ObservationController;
import agent.next.device.ObservationOptions;
import agent.next.device.ObservationRef;
import agent.next.device.ObservationStatus;
import agent.next.device.Point;
import agent.next.device.TargetController;
import agent.next.device.TargetHandle;
import agent.next.device.TargetInfo;
import agent.next.device.TargetSpec;
import agent.next.device.TaskController;
import agent.next.device.UiNode;
import android.view.Surface;
import agent.phone.connection.Capabilities;
import agent.phone.connection.ConnectionDriver;
import agent.phone.connection.ConnectionDriverFactory;
import agent.phone.connection.ConnectionState;
import agent.phone.connection.DisplaySession;
import agent.phone.connection.FrameEvidence;
import agent.phone.connection.TargetObservation;
import agent.phone.connection.TargetSession;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Synchronous {@link DeviceApi} facade over the capability connection
 * coordinator. Call it from a worker thread: every operation blocks until the
 * coordinator publishes a terminal state or the timeout expires.
 *
 * <p>The coordinator's own deadline is 10 seconds; the default here is 15
 * seconds so cleanup/error states have a chance to surface first.</p>
 */
public final class BlockingDeviceApi implements DeviceApi, AndroidPreviewController, ConnectionDriver.Listener {

    private static final long DEFAULT_TIMEOUT_MS = 15_000L;
    private static final String DISPLAY_NAME = "agent-phone";

    private final ConnectionDriver driver;
    private final long timeoutMillis;
    private final Object monitor = new Object();

    private ConnectionState state;
    private long stateSequence;
    private boolean closed;
    private int targetDisplayId = -1;

    private final DisplayController displays = new Displays();
    private final TargetController targets = new Targets();
    private final ObservationController observations = new Observations();
    private final InputController input = new Inputs();
    private final TaskController tasks = new Tasks();

    public BlockingDeviceApi(ConnectionDriverFactory factory) {
        this(factory, DEFAULT_TIMEOUT_MS);
    }

    public BlockingDeviceApi(ConnectionDriverFactory factory, long timeoutMillis) {
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.timeoutMillis = timeoutMillis;
        this.driver = factory.open(this::onStateChanged);
        if (driver == null) {
            throw new NullPointerException("factory returned no driver");
        }
    }

    @Override
    public void onStateChanged(ConnectionState next) {
        if (next == null) {
            return;
        }
        synchronized (monitor) {
            state = next;
            stateSequence++;
            monitor.notifyAll();
        }
    }

    @Override
    public void connect() throws DeviceException {
        ensureOpen();
        ConnectionState current = snapshot();
        if (current != null && current.status == ConnectionState.Status.CONNECTED) {
            return;
        }
        if (current != null && failure(current, true)) {
            throw mapState(current, "connect");
        }
        if (current != null && !current.canConnect) {
            current = await(stateSequence(),
                    next -> next.canConnect || next.status == ConnectionState.Status.CONNECTED,
                    true, "connect-ready");
            if (current.status == ConnectionState.Status.CONNECTED) {
                return;
            }
        }
        long sequence = stateSequence();
        driver.connect();
        await(sequence, next -> next.status == ConnectionState.Status.CONNECTED, true, "connect");
    }

    @Override
    public DeviceCapabilities capabilities() throws DeviceException {
        ConnectionState connected = requireConnected();
        Capabilities capabilities = connected.capabilities;
        EnumSet<DeviceFeature> features = EnumSet.noneOf(DeviceFeature.class);
        if (capabilities.supported) {
            features = EnumSet.allOf(DeviceFeature.class);
            if (!capabilities.previewMirror) {
                features.remove(DeviceFeature.PREVIEW_MIRROR);
            }
        }
        return new DeviceCapabilities(capabilities.protocol, capabilities.api,
                capabilities.serviceUid, capabilities.appUid, capabilities.supported, features);
    }

    @Override
    public DisplayController displays() {
        return displays;
    }

    @Override
    public TargetController targets() {
        return targets;
    }

    @Override
    public ObservationController observations() {
        return observations;
    }

    @Override
    public InputController input() {
        return input;
    }

    @Override
    public TaskController tasks() {
        return tasks;
    }

    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            monitor.notifyAll();
        }
        driver.close();
    }

    private final class Displays implements DisplayController {

        @Override
        public DisplayHandle create(DisplaySpec spec) throws DeviceException {
            requireReady();
            if (spec == null) {
                throw rejected("display spec is required");
            }
            if (spec.width() != DisplaySpec.standard().width()
                    || spec.height() != DisplaySpec.standard().height()
                    || spec.densityDpi() != DisplaySpec.standard().densityDpi()) {
                throw rejected("capability supports only 720x1280/240 display");
            }
            ConnectionState before = snapshot();
            if (before != null && before.display != null
                    && before.display.status == DisplaySession.Status.ACTIVE) {
                throw busy("a private display is already active");
            }
            long sequence = stateSequence();
            driver.createDisplay();
            ConnectionState after = await(sequence,
                    next -> next.displayOperation == ConnectionState.DisplayOperation.NONE
                            && next.display != null
                            && next.status == ConnectionState.Status.CONNECTED,
                    false, "create display");
            if (after.display.status != DisplaySession.Status.ACTIVE) {
                throw new DeviceException(DeviceException.Code.INTERNAL,
                        "display was created in status " + after.display.status);
            }
            return new DisplayHandle(after.display.token, after.display.generation);
        }

        @Override
        public DisplayInfo check(DisplayHandle display) throws DeviceException {
            DisplaySession active = activeDisplay(display);
            requireReady();
            long sequence = stateSequence();
            driver.checkDisplay();
            ConnectionState after = await(sequence,
                    next -> next.displayOperation == ConnectionState.DisplayOperation.NONE
                            && next.display != null
                            && next.status == ConnectionState.Status.CONNECTED,
                    false, "check display");
            if (!active.sameIdentity(after.display)) {
                throw stale("display identity changed");
            }
            if (after.display.status != DisplaySession.Status.ACTIVE) {
                throw stale("display is not active");
            }
            return displayInfo(after.display);
        }

        @Override
        public void release(DisplayHandle display) throws DeviceException {
            DisplaySession active = activeDisplay(display);
            requireReady();
            long sequence = stateSequence();
            driver.releaseDisplay();
            ConnectionState after = await(sequence,
                    next -> next.displayOperation == ConnectionState.DisplayOperation.NONE
                            && next.display != null
                            && next.status == ConnectionState.Status.CONNECTED,
                    false, "release display");
            if (!active.sameIdentity(after.display)) {
                throw stale("display identity changed");
            }
            targetDisplayId = -1;
            if (after.display.status == DisplaySession.Status.CLEANUP_FAILED) {
                throw new DeviceException(DeviceException.Code.INTERNAL, "display cleanup failed");
            }
            if (after.display.status != DisplaySession.Status.RELEASED) {
                throw new DeviceException(DeviceException.Code.INTERNAL,
                        "unexpected display release status " + after.display.status);
            }
        }
    }

    private final class Targets implements TargetController {

        @Override
        public TargetHandle open(DisplayHandle display, TargetSpec spec) throws DeviceException {
            DisplaySession active = activeDisplay(display);
            requireReady();
            if (spec == null) {
                throw rejected("target spec is required");
            }
            ConnectionState before = snapshot();
            if (before.target != null) {
                throw busy("another target is already active");
            }

            long bindSequence = stateSequence();
            driver.bindTarget(spec.taskId(),
                    spec.allowedPackages().toArray(new String[0]), spec.packageName());
            ConnectionState bound = await(bindSequence,
                    next -> next.targetOperation == ConnectionState.TargetOperation.NONE
                            && next.target != null
                            && next.status == ConnectionState.Status.CONNECTED,
                    false, "bind target");
            if (!active.sameIdentity(bound.target.display)
                    || !spec.taskId().equals(bound.target.taskId)
                    || !spec.packageName().equals(bound.target.packageName)) {
                throw stale("bound target does not match the request");
            }

            long launchSequence = stateSequence();
            driver.launchTarget();
            ConnectionState launched = await(launchSequence,
                    next -> next.targetOperation == ConnectionState.TargetOperation.NONE
                            && next.target != null
                            && next.status == ConnectionState.Status.CONNECTED,
                    false, "launch target");
            if (!bound.target.sameIdentity(launched.target)) {
                throw stale("target identity changed during launch");
            }
            targetDisplayId = launched.target.display.displayId;
            return new TargetHandle(launched.target.token, launched.target.generation,
                    launched.target.taskId, launched.target.packageName);
        }

        @Override
        public TargetInfo state(TargetHandle target) throws DeviceException {
            return targetInfo(target);
        }

        @Override
        public void release(TargetHandle target) throws DeviceException {
            targetFor(target);
            requireReady();
            long sequence = stateSequence();
            driver.releaseTarget();
            await(sequence, next -> next.target == null, false, "release target");
            targetDisplayId = -1;
        }
    }

    private final class Observations implements ObservationController {

        @Override
        public Observation observe(TargetHandle target, ObservationOptions options)
                throws DeviceException {
            targetFor(target);
            requireReady();
            if (options == null) {
                throw rejected("observation options are required");
            }
            ConnectionState before = snapshot();
            long previousSequence = before.observation == null ? -1 : before.observation.sequence;
            long sequence = stateSequence();
            driver.observeTarget();
            ConnectionState after = await(sequence,
                    next -> next.targetOperation == ConnectionState.TargetOperation.NONE
                            && next.observation != null
                            && next.observation.sequence != previousSequence
                            && next.status == ConnectionState.Status.CONNECTED,
                    false, "observe target");
            return mapObservation(after.observation);
        }

        @Override
        public Frame captureFrame(TargetHandle target) throws DeviceException {
            targetFor(target);
            requireReady();
            ConnectionState before = snapshot();
            long previousSequence = before.frame == null ? -1 : before.frame.evidence.sequence;
            long sequence = stateSequence();
            driver.captureTarget();
            ConnectionState after = await(sequence,
                    next -> next.targetOperation == ConnectionState.TargetOperation.NONE
                            && next.frame != null
                            && next.frame.evidence.sequence != previousSequence
                            && next.status == ConnectionState.Status.CONNECTED,
                    false, "capture frame");
            FrameEvidence.Capture capture = after.frame;
            return new Frame(capture.evidence.width, capture.evidence.height,
                    capture.png(), Instant.now(), capture.evidence.sequence);
        }
    }

    private final class Inputs implements InputController {

        @Override
        public InputReceipt tap(TargetHandle target, Observation observation, Point point)
                throws DeviceException {
            TargetSession session = targetFor(target);
            requireReady();
            requirePrivateDisplay(session);
            Observation current = currentObservation(observation);
            requirePoint(current, point);
            long sequence = stateSequence();
            driver.tap(point.x(), point.y());
            awaitInput(sequence, "tap");
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }

        @Override
        public InputReceipt setText(TargetHandle target, Observation observation,
                String nodeRef, String text) throws DeviceException {
            TargetSession session = targetFor(target);
            requireReady();
            requirePrivateDisplay(session);
            Observation current = currentObservation(observation);
            if (nodeRef == null || nodeRef.isEmpty() || nodeRef.length() > 80) {
                throw rejected("nodeRef must be 1..80 characters");
            }
            if (text == null || text.isEmpty() || text.length() > 120
                    || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
                throw rejected("text must be 1..120 characters without newlines");
            }
            UiNode node = current.node(nodeRef).orElse(null);
            if (node == null) {
                throw stale("node is not part of the current observation");
            }
            if (!node.hasFlag(NodeFlag.EDITABLE) || node.hasFlag(NodeFlag.PASSWORD)) {
                throw rejected("node is not a non-password editable field");
            }
            long sequence = stateSequence();
            driver.setText(nodeRef, text);
            awaitInput(sequence, "set text");
            return new InputReceipt(InputStatus.VERIFIED, true, true, text, Instant.now());
        }

        @Override
        public InputReceipt swipe(TargetHandle target, Observation observation,
                Point from, Point to, int durationMs) throws DeviceException {
            TargetSession session = targetFor(target);
            requireReady();
            requirePrivateDisplay(session);
            Observation current = currentObservation(observation);
            if (durationMs < 100 || durationMs > 2000) {
                throw rejected("swipe duration must be 100..2000 ms");
            }
            requirePoint(current, from);
            requirePoint(current, to);
            long sequence = stateSequence();
            driver.swipe(from.x(), from.y(), to.x(), to.y(), durationMs);
            awaitInput(sequence, "swipe");
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }

        @Override
        public InputReceipt back(TargetHandle target, Observation observation)
                throws DeviceException {
            TargetSession session = targetFor(target);
            requireReady();
            requirePrivateDisplay(session);
            currentObservation(observation);
            long sequence = stateSequence();
            driver.back();
            awaitInput(sequence, "back");
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }
    }

    private final class Tasks implements TaskController {

        @Override
        public TargetInfo moveToMain(TargetHandle target) throws DeviceException {
            targetFor(target);
            if (targetDisplayId == 0) {
                return targetInfo(target);
            }
            requireReady();
            move(0, "move to main");
            targetDisplayId = 0;
            return targetInfo(target);
        }

        @Override
        public TargetInfo moveToPrivate(TargetHandle target, DisplayHandle display)
                throws DeviceException {
            targetFor(target);
            DisplaySession active = activeDisplay(display);
            if (targetDisplayId == active.displayId) {
                return targetInfo(target);
            }
            requireReady();
            move(active.displayId, "move to private");
            targetDisplayId = active.displayId;
            return targetInfo(target);
        }
    }

    private void move(int displayId, String operation) throws DeviceException {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean moved = new AtomicBoolean(false);
        driver.moveTargetToDisplay(displayId, accepted ->
                {
                    moved.set(Boolean.TRUE.equals(accepted));
                    latch.countDown();
                });
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new DeviceException(DeviceException.Code.TIMEOUT,
                        operation + " timed out waiting for the coordinator");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeviceException(DeviceException.Code.INTERNAL,
                    operation + " interrupted", interrupted);
        }
        if (!moved.get()) {
            throw rejected(operation + " was rejected");
        }
        await(0, next -> next.targetOperation == ConnectionState.TargetOperation.NONE
                && next.status == ConnectionState.Status.CONNECTED
                && next.target != null, false, operation);
    }

    private void awaitInput(long minimumSequence, String operation) throws DeviceException {
        await(minimumSequence, next -> next.targetOperation == ConnectionState.TargetOperation.NONE
                && next.status == ConnectionState.Status.CONNECTED
                && next.target != null, false, operation);
    }

    private ConnectionState await(long minimumSequence, Predicate<ConnectionState> condition,
            boolean allowDisconnected, String operation) throws DeviceException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (monitor) {
            while (true) {
                if (closed) {
                    throw new DeviceException(DeviceException.Code.UNAVAILABLE, "device api is closed");
                }
                if (state != null && stateSequence > minimumSequence && condition.test(state)) {
                    return state;
                }
                if (state != null && stateSequence > minimumSequence
                        && failure(state, allowDisconnected)) {
                    throw mapState(state, operation);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw timeout(operation);
                }
                try {
                    monitor.wait(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DeviceException(DeviceException.Code.INTERNAL,
                            operation + " interrupted", interrupted);
                }
            }
        }
    }

    private static boolean failure(ConnectionState state, boolean allowDisconnected) {
        if (state.status == ConnectionState.Status.ERROR
                || state.status == ConnectionState.Status.UNSUPPORTED
                || state.status == ConnectionState.Status.NEEDS_SHIZUKU) {
            return true;
        }
        return state.status == ConnectionState.Status.DISCONNECTED && !allowDisconnected;
    }

    private DeviceException timeout(String operation) {
        ConnectionState current = snapshot();
        if (current != null && (current.status == ConnectionState.Status.PERMISSION_REQUIRED
                || current.status == ConnectionState.Status.AUTHORIZING)) {
            return new DeviceException(DeviceException.Code.REJECTED,
                    operation + ": Shizuku permission was not granted");
        }
        if (current != null && current.reason == ConnectionState.Reason.RPC_BUSY) {
            return new DeviceException(DeviceException.Code.BUSY,
                    operation + ": device RPC is busy");
        }
        return new DeviceException(DeviceException.Code.TIMEOUT, operation + " timed out");
    }

    private static DeviceException mapState(ConnectionState state, String operation) {
        DeviceException.Code code;
        if (state.status == ConnectionState.Status.UNSUPPORTED) {
            code = DeviceException.Code.PROTOCOL;
        } else if (state.status == ConnectionState.Status.NEEDS_SHIZUKU) {
            code = DeviceException.Code.UNAVAILABLE;
        } else if (state.status == ConnectionState.Status.PERMISSION_REQUIRED) {
            code = DeviceException.Code.REJECTED;
        } else if (state.status == ConnectionState.Status.DISCONNECTED) {
            code = DeviceException.Code.UNAVAILABLE;
        } else {
            code = mapReason(state.reason);
        }
        return new DeviceException(code,
                operation + " failed: " + state.status + "/" + state.reason);
    }

    private static DeviceException.Code mapReason(ConnectionState.Reason reason) {
        if (reason == ConnectionState.Reason.TIMEOUT) {
            return DeviceException.Code.TIMEOUT;
        }
        if (reason == ConnectionState.Reason.RPC_BUSY
                || reason == ConnectionState.Reason.BLOCKED) {
            return DeviceException.Code.BUSY;
        }
        if (reason == ConnectionState.Reason.LOST
                || reason == ConnectionState.Reason.NO_SHIZUKU
                || reason == ConnectionState.Reason.ENVIRONMENT
                || reason == ConnectionState.Reason.USER) {
            return DeviceException.Code.UNAVAILABLE;
        }
        if (reason == ConnectionState.Reason.MALFORMED
                || reason == ConnectionState.Reason.OLD_VERSION) {
            return DeviceException.Code.PROTOCOL;
        }
        if (reason == ConnectionState.Reason.DENIED) {
            return DeviceException.Code.REJECTED;
        }
        return DeviceException.Code.INTERNAL;
    }

    private ConnectionState requireConnected() throws DeviceException {
        ensureOpen();
        ConnectionState current = snapshot();
        if (current == null || current.status != ConnectionState.Status.CONNECTED
                || current.capabilities == null) {
            throw new DeviceException(DeviceException.Code.UNAVAILABLE, "device is not connected");
        }
        return current;
    }

    @Override
    public void startPreview(DisplayHandle display, Surface surface) throws DeviceException {
        activeDisplay(display);
        requireReady();
        if (surface == null || !surface.isValid()) {
            throw rejected("preview surface is invalid");
        }
        if (!driver.startPreviewMirror(surface)) {
            throw rejected("preview mirror was rejected");
        }
    }

    @Override
    public void stopPreview(DisplayHandle display) throws DeviceException {
        activeDisplay(display);
        driver.stopPreviewMirror();
    }

    @Override
    public void userTap(TargetHandle target, int x, int y) throws DeviceException {
        targetFor(target);
        requireReady();
        if (x < 0 || y < 0 || !driver.userTap(x, y)) {
            throw rejected("user tap was rejected");
        }
    }

    @Override
    public void userSwipe(TargetHandle target, int startX, int startY, int endX, int endY,
            int durationMs) throws DeviceException {
        targetFor(target);
        requireReady();
        if (startX < 0 || startY < 0 || endX < 0 || endY < 0
                || durationMs <= 0 || !driver.userSwipe(startX, startY, endX, endY, durationMs)) {
            throw rejected("user swipe was rejected");
        }
    }

    private ConnectionState requireReady() throws DeviceException {
        ConnectionState current = requireConnected();
        if (!current.canUseDisplay) {
            throw busy("device is busy: display=" + current.displayOperation
                    + ", target=" + current.targetOperation);
        }
        return current;
    }

    private DisplaySession activeDisplay(DisplayHandle handle) throws DeviceException {
        if (handle == null) {
            throw rejected("display handle is required");
        }
        ConnectionState current = requireConnected();
        DisplaySession display = current.display;
        if (display == null || !display.token.equals(handle.token())
                || display.generation != handle.generation()
                || display.status != DisplaySession.Status.ACTIVE) {
            throw stale("display handle is not active");
        }
        return display;
    }

    private TargetSession targetFor(TargetHandle handle) throws DeviceException {
        if (handle == null) {
            throw rejected("target handle is required");
        }
        ConnectionState current = requireConnected();
        TargetSession target = current.target;
        if (target == null || !target.token.equals(handle.token())
                || target.generation != handle.generation()
                || !target.taskId.equals(handle.taskId())
                || !target.packageName.equals(handle.packageName())) {
            throw stale("target handle is not active");
        }
        return target;
    }

    private Observation currentObservation(Observation observation) throws DeviceException {
        if (observation == null || observation.status() != ObservationStatus.READY) {
            throw rejected("a ready observation is required");
        }
        ObservationRef reference = observation.reference().orElse(null);
        if (reference == null) {
            throw stale("observation reference is missing");
        }
        ConnectionState current = requireConnected();
        TargetObservation stored = current.observation;
        if (stored == null || stored.status != TargetObservation.Status.READY
                || !stored.observationId.equals(reference.observationId())
                || stored.sequence != reference.sequence()
                || stored.focusedWindowId != observation.windowId()) {
            throw stale("observation is not current");
        }
        return observation;
    }

    private void requirePrivateDisplay(TargetSession target) throws DeviceException {
        if (targetDisplayId == 0) {
            throw rejected("target is on the main screen; user takeover is required");
        }
    }

    private void requirePoint(Observation observation, Point point) throws DeviceException {
        if (point == null) {
            throw rejected("point is required");
        }
        NodeBounds bounds = observation.windowBounds().orElse(null);
        if (bounds == null || !bounds.contains(point)) {
            throw rejected("point is outside the observed target window");
        }
    }

    private TargetInfo targetInfo(TargetHandle handle) throws DeviceException {
        TargetSession target = targetFor(handle);
        int displayId = targetDisplayId >= 0 ? targetDisplayId : target.display.displayId;
        return new TargetInfo(target.taskId, target.packageName, target.launcherClass,
                target.uid, displayId, true, target.generation);
    }

    private static DisplayInfo displayInfo(DisplaySession display) throws DeviceException {
        return new DisplayInfo(display.displayId, display.uniqueId, DISPLAY_NAME,
                display.width, display.height, display.densityDpi,
                display.ownerUid, display.generation);
    }

    private Observation mapObservation(TargetObservation source) {
        ObservationStatus status = mapStatus(source.status);
        boolean ready = status == ObservationStatus.READY;
        ObservationRef reference = ready
                ? new ObservationRef(source.observationId, source.sequence)
                : null;
        NodeBounds windowBounds = ready && source.windowBounds != null
                ? new NodeBounds(source.windowBounds.left, source.windowBounds.top,
                        source.windowBounds.right, source.windowBounds.bottom)
                : null;
        List<UiNode> nodes = new ArrayList<>();
        if (ready) {
            for (TargetObservation.Node node : source.nodes) {
                EnumSet<NodeFlag> flags = EnumSet.noneOf(NodeFlag.class);
                if (node.clickable) {
                    flags.add(NodeFlag.CLICKABLE);
                }
                if (node.editable) {
                    flags.add(NodeFlag.EDITABLE);
                }
                if (node.enabled) {
                    flags.add(NodeFlag.ENABLED);
                }
                if (node.focused) {
                    flags.add(NodeFlag.FOCUSED);
                }
                if (node.password) {
                    flags.add(NodeFlag.PASSWORD);
                }
                if (node.scrollable) {
                    flags.add(NodeFlag.SCROLLABLE);
                }
                if (node.visible) {
                    flags.add(NodeFlag.VISIBLE_TO_USER);
                }
                nodes.add(new UiNode(node.ref, node.index, node.parent, node.windowId,
                        node.resourceId, node.className, node.text, node.description,
                        new NodeBounds(node.bounds.left, node.bounds.top,
                                node.bounds.right, node.bounds.bottom),
                        flags, node.textTruncated));
            }
        }
        int displayId = targetDisplayId >= 0 ? targetDisplayId : source.target.display.displayId;
        // The current activity is not part of the capability observation yet; keep it empty
        // rather than reporting the launcher class as if it were the current activity.
        return new Observation(status, reference, source.target.packageName, "", displayId,
                ready ? source.focusedWindowId : -1, ready, Instant.now(), source.lookupCount,
                source.truncated, windowBounds, nodes);
    }

    private static ObservationStatus mapStatus(TargetObservation.Status status) {
        switch (status) {
            case READY:
                return ObservationStatus.READY;
            case FOREGROUND_CONFLICT:
                return ObservationStatus.FOREGROUND_CONFLICT;
            case TARGET_NOT_READY:
                return ObservationStatus.TARGET_NOT_READY;
            case FOREIGN_WINDOW:
                return ObservationStatus.FOREIGN_WINDOW;
            case WINDOW_CHANGED:
                return ObservationStatus.WINDOW_CHANGED;
            default:
                throw new AssertionError("unknown observation status: " + status);
        }
    }

    private void ensureOpen() throws DeviceException {
        synchronized (monitor) {
            if (closed) {
                throw new DeviceException(DeviceException.Code.UNAVAILABLE, "device api is closed");
            }
        }
    }

    private ConnectionState snapshot() {
        synchronized (monitor) {
            return state;
        }
    }

    private long stateSequence() {
        synchronized (monitor) {
            return stateSequence;
        }
    }

    private static DeviceException rejected(String message) {
        return new DeviceException(DeviceException.Code.REJECTED, message);
    }

    private static DeviceException stale(String message) {
        return new DeviceException(DeviceException.Code.STALE, message);
    }

    private static DeviceException busy(String message) {
        return new DeviceException(DeviceException.Code.BUSY, message);
    }
}
