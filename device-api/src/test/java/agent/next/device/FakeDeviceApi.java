package agent.next.device;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory reference implementation of {@link DeviceApi} for contract tests
 * and early brain-loop work. It never touches Android.
 */
public final class FakeDeviceApi implements DeviceApi {

    private boolean closed;
    private int nextDisplayId = 101;
    private int nextTargetId = 1;
    private int nextObservationId = 1;
    private long nextFrameSequence = 1;

    private final Map<String, FakeDisplay> displays = new LinkedHashMap<>();
    private final Map<String, FakeTarget> targets = new LinkedHashMap<>();
    private final Map<String, FakeObservation> observations = new LinkedHashMap<>();

    private final DisplayController displayController = new Displays();
    private final TargetController targetController = new Targets();
    private final ObservationController observationController = new Observations();
    private final InputController inputController = new Inputs();
    private final TaskController taskController = new Tasks();

    @Override
    public void connect() throws DeviceException {
        ensureOpen();
    }

    @Override
    public DeviceCapabilities capabilities() throws DeviceException {
        ensureOpen();
        return new DeviceCapabilities(11, 36, 2000, 10000, true,
                EnumSet.allOf(DeviceFeature.class));
    }

    @Override
    public DisplayController displays() {
        return displayController;
    }

    @Override
    public TargetController targets() {
        return targetController;
    }

    @Override
    public ObservationController observations() {
        return observationController;
    }

    @Override
    public InputController input() {
        return inputController;
    }

    @Override
    public TaskController tasks() {
        return taskController;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        displays.clear();
        targets.clear();
        observations.clear();
    }

    private void ensureOpen() throws DeviceException {
        if (closed) {
            throw new DeviceException(DeviceException.Code.UNAVAILABLE, "fake device is closed");
        }
    }

    private FakeDisplay display(DisplayHandle handle) throws DeviceException {
        if (handle == null) {
            throw rejected("display handle is required");
        }
        FakeDisplay display = displays.get(handle.token());
        if (display == null || display.handle.generation() != handle.generation()) {
            throw stale("display is not active: " + handle.token());
        }
        return display;
    }

    private FakeTarget target(TargetHandle handle) throws DeviceException {
        if (handle == null) {
            throw rejected("target handle is required");
        }
        FakeTarget target = targets.get(handle.token());
        if (target == null || target.handle.generation() != handle.generation()) {
            throw stale("target is not active: " + handle.token());
        }
        return target;
    }

    private FakeObservation observation(TargetHandle target, Observation value)
            throws DeviceException {
        if (value == null) {
            throw rejected("observation is required");
        }
        if (value.status() != ObservationStatus.READY) {
            throw rejected("observation is not ready: " + value.status());
        }
        ObservationRef reference = value.reference().orElse(null);
        if (reference == null) {
            throw stale("observation has no reference");
        }
        FakeObservation stored = observations.get(reference.observationId());
        if (stored == null || !stored.targetToken.equals(target.token())) {
            throw stale("observation is not active for this target");
        }
        return stored;
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

    private final class Displays implements DisplayController {

        @Override
        public DisplayHandle create(DisplaySpec spec) throws DeviceException {
            ensureOpen();
            if (spec == null) {
                throw rejected("display spec is required");
            }
            if (spec.width() != 720 || spec.height() != 1280 || spec.densityDpi() != 240) {
                throw rejected("fake supports only the standard 720x1280/240 display");
            }
            int displayId = nextDisplayId++;
            DisplayHandle handle = new DisplayHandle("fake-display-" + displayId, 1);
            DisplayInfo info = new DisplayInfo(displayId, "fake:display:" + displayId,
                    "agent-phone", 720, 1280, 240, 2000, 1);
            displays.put(handle.token(), new FakeDisplay(handle, info));
            return handle;
        }

        @Override
        public DisplayInfo check(DisplayHandle display) throws DeviceException {
            ensureOpen();
            return display(display).info;
        }

        @Override
        public void release(DisplayHandle display) throws DeviceException {
            ensureOpen();
            FakeDisplay found = display(display);
            Iterator<Map.Entry<String, FakeTarget>> targetsIterator =
                    targets.entrySet().iterator();
            while (targetsIterator.hasNext()) {
                FakeTarget target = targetsIterator.next().getValue();
                if (target.displayToken.equals(found.handle.token())) {
                    removeObservations(target.handle.token());
                    targetsIterator.remove();
                }
            }
            displays.remove(found.handle.token());
        }
    }

    private final class Targets implements TargetController {

        @Override
        public TargetHandle open(DisplayHandle display, TargetSpec spec) throws DeviceException {
            ensureOpen();
            FakeDisplay foundDisplay = display(display);
            if (spec == null) {
                throw rejected("target spec is required");
            }
            if (!targets.isEmpty()) {
                throw busy("fake supports one active target");
            }
            int targetId = nextTargetId++;
            TargetHandle handle = new TargetHandle("fake-target-" + targetId, 1,
                    spec.taskId(), spec.packageName());
            TargetInfo info = new TargetInfo(spec.taskId(), spec.packageName(),
                    "fake.MainActivity", 10000 + targetId, foundDisplay.info.displayId(),
                    true, 1);
            targets.put(handle.token(), new FakeTarget(handle, info, foundDisplay.handle.token()));
            return handle;
        }

        @Override
        public TargetInfo state(TargetHandle target) throws DeviceException {
            ensureOpen();
            return target(target).info;
        }

        @Override
        public void release(TargetHandle target) throws DeviceException {
            ensureOpen();
            FakeTarget found = target(target);
            removeObservations(found.handle.token());
            targets.remove(found.handle.token());
        }
    }

    private final class Observations implements ObservationController {

        @Override
        public Observation observe(TargetHandle target, ObservationOptions options)
                throws DeviceException {
            ensureOpen();
            FakeTarget found = target(target);
            if (options == null) {
                throw rejected("observation options are required");
            }
            int observationId = nextObservationId++;
            String id = "fake-observation-" + observationId;
            long sequence = observationId;
            List<UiNode> nodes = new ArrayList<>();
            nodes.add(node(id + ":0", 0, -1, 1000, "android:id/root",
                    "android.widget.FrameLayout", "", "",
                    new NodeBounds(0, 0, 720, 1280), EnumSet.of(NodeFlag.VISIBLE_TO_USER)));
            nodes.add(node(id + ":1", 1, 0, 1000, "com.example.shop:id/search",
                    "android.widget.EditText", "", "Search",
                    new NodeBounds(20, 100, 700, 160),
                    EnumSet.of(NodeFlag.EDITABLE, NodeFlag.FOCUSED,
                            NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED)));
            nodes.add(node(id + ":2", 2, 0, 1000, "com.example.shop:id/submit",
                    "android.widget.Button", "Search", "",
                    new NodeBounds(20, 200, 700, 260),
                    EnumSet.of(NodeFlag.CLICKABLE, NodeFlag.VISIBLE_TO_USER,
                            NodeFlag.ENABLED)));
            nodes.add(node(id + ":3", 3, 0, 1000, "com.example.shop:id/password",
                    "android.widget.EditText", "", "Password",
                    new NodeBounds(20, 300, 700, 360),
                    EnumSet.of(NodeFlag.EDITABLE, NodeFlag.PASSWORD,
                            NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED)));
            Observation observation = new Observation(
                    ObservationStatus.READY,
                    new ObservationRef(id, sequence),
                    found.info.packageName(),
                    found.info.activityName(),
                    found.info.displayId(),
                    1000,
                    true,
                    Instant.now(),
                    nodes.size(),
                    false,
                    new NodeBounds(0, 0, 720, 1280),
                    nodes);
            observations.put(id, new FakeObservation(observation, found.handle.token()));
            return observation;
        }

        @Override
        public Frame captureFrame(TargetHandle target) throws DeviceException {
            ensureOpen();
            target(target);
            byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
            return new Frame(720, 1280, png, Instant.now(), nextFrameSequence++);
        }
    }

    private final class Inputs implements InputController {

        @Override
        public InputReceipt tap(TargetHandle target, Observation observation, Point point)
                throws DeviceException {
            ensureOpen();
            FakeObservation stored = observation(target, observation);
            requirePoint(stored, point);
            consume(stored);
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }

        @Override
        public InputReceipt setText(TargetHandle target, Observation observation,
                String nodeRef, String text) throws DeviceException {
            ensureOpen();
            FakeObservation stored = observation(target, observation);
            if (nodeRef == null || nodeRef.trim().isEmpty()) {
                throw rejected("nodeRef is required");
            }
            if (text == null || text.indexOf('\u0000') >= 0) {
                throw rejected("text is required and must not contain NUL");
            }
            if (text.codePointCount(0, text.length()) > 256) {
                throw rejected("text exceeds 256 code points");
            }
            UiNode node = findNode(stored.observation, nodeRef);
            if (!node.hasFlag(NodeFlag.EDITABLE) || node.hasFlag(NodeFlag.PASSWORD)) {
                throw rejected("node is not a non-password editable field");
            }
            consume(stored);
            return new InputReceipt(InputStatus.VERIFIED, true, true, text, Instant.now());
        }

        @Override
        public InputReceipt swipe(TargetHandle target, Observation observation,
                Point from, Point to, int durationMs) throws DeviceException {
            ensureOpen();
            FakeObservation stored = observation(target, observation);
            if (durationMs < 100 || durationMs > 1000) {
                throw rejected("swipe duration must be 100..1000 ms");
            }
            requirePoint(stored, from);
            requirePoint(stored, to);
            consume(stored);
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }

        @Override
        public InputReceipt back(TargetHandle target, Observation observation)
                throws DeviceException {
            ensureOpen();
            FakeObservation stored = observation(target, observation);
            consume(stored);
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }
    }

    private final class Tasks implements TaskController {

        @Override
        public TargetInfo moveToMain(TargetHandle target) throws DeviceException {
            ensureOpen();
            FakeTarget found = target(target);
            TargetInfo old = found.info;
            TargetInfo moved = new TargetInfo(old.taskId(), old.packageName(),
                    old.activityName(), old.uid(), 0, true, old.generation());
            found.info = moved;
            return moved;
        }

        @Override
        public TargetInfo moveToPrivate(TargetHandle target, DisplayHandle display)
                throws DeviceException {
            ensureOpen();
            FakeTarget found = target(target);
            FakeDisplay foundDisplay = display(display);
            TargetInfo old = found.info;
            TargetInfo moved = new TargetInfo(old.taskId(), old.packageName(),
                    old.activityName(), old.uid(), foundDisplay.info.displayId(),
                    true, old.generation());
            found.info = moved;
            return moved;
        }
    }

    private void requirePoint(FakeObservation observation, Point point) throws DeviceException {
        if (point == null) {
            throw rejected("point is required");
        }
        NodeBounds bounds = observation.observation.windowBounds().orElse(NodeBounds.EMPTY);
        if (!bounds.contains(point)) {
            throw rejected("point is outside the target window");
        }
    }

    private void consume(FakeObservation observation) {
        ObservationRef reference = observation.observation.reference().orElse(null);
        if (reference != null) {
            observations.remove(reference.observationId());
        }
    }

    private static UiNode findNode(Observation observation, String ref) throws DeviceException {
        for (UiNode node : observation.nodes()) {
            if (node.ref().equals(ref)) {
                return node;
            }
        }
        throw rejected("unknown nodeRef: " + ref);
    }

    private static UiNode node(String ref, int index, int parentIndex, int windowId,
            String viewId, String className, String text, String description,
            NodeBounds bounds, Set<NodeFlag> flags) {
        return new UiNode(ref, index, parentIndex, windowId, viewId, className, text,
                description, bounds, flags, false);
    }

    private void removeObservations(String targetToken) {
        Iterator<Map.Entry<String, FakeObservation>> iterator =
                observations.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().targetToken.equals(targetToken)) {
                iterator.remove();
            }
        }
    }

    private static final class FakeDisplay {
        private final DisplayHandle handle;
        private final DisplayInfo info;

        private FakeDisplay(DisplayHandle handle, DisplayInfo info) {
            this.handle = handle;
            this.info = info;
        }
    }

    private static final class FakeTarget {
        private final TargetHandle handle;
        private final String displayToken;
        private TargetInfo info;

        private FakeTarget(TargetHandle handle, TargetInfo info, String displayToken) {
            this.handle = handle;
            this.displayToken = displayToken;
            this.info = info;
        }
    }

    private static final class FakeObservation {
        private final Observation observation;
        private final String targetToken;

        private FakeObservation(Observation observation, String targetToken) {
            this.observation = observation;
            this.targetToken = targetToken;
        }
    }
}
