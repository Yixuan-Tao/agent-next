package agent.next.brain.loop;

import agent.next.device.DeviceApi;
import agent.next.device.DeviceCapabilities;
import agent.next.device.DeviceException;
import agent.next.device.DeviceFeature;
import agent.next.device.DisplayController;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/** Tiny scripted device for the fast-loop host tests. */
public final class LoopFakeDevice implements DeviceApi {

    public LoopFakeDevice() {
    }

    private boolean closed;
    private boolean results;
    private String text = "";
    private int observationSequence;

    @Override
    public void connect() throws DeviceException {
        if (closed) {
            throw new DeviceException(DeviceException.Code.UNAVAILABLE, "closed");
        }
    }

    @Override
    public DeviceCapabilities capabilities() throws DeviceException {
        connect();
        return new DeviceCapabilities(11, 36, 2000, 10000, true,
                EnumSet.allOf(DeviceFeature.class));
    }

    @Override
    public DisplayController displays() {
        return new DisplayController() {
            @Override
            public agent.next.device.DisplayHandle create(agent.next.device.DisplaySpec spec) {
                throw new UnsupportedOperationException("display is not used by the loop test");
            }

            @Override
            public agent.next.device.DisplayInfo check(agent.next.device.DisplayHandle display) {
                throw new UnsupportedOperationException("display is not used by the loop test");
            }

            @Override
            public void release(agent.next.device.DisplayHandle display) {
                throw new UnsupportedOperationException("display is not used by the loop test");
            }
        };
    }

    @Override
    public TargetController targets() {
        return new TargetController() {
            @Override
            public TargetHandle open(agent.next.device.DisplayHandle display, TargetSpec spec) {
                throw new UnsupportedOperationException("target is supplied by the test");
            }

            @Override
            public TargetInfo state(TargetHandle target) {
                throw new UnsupportedOperationException("target is supplied by the test");
            }

            @Override
            public void release(TargetHandle target) {
                throw new UnsupportedOperationException("target is supplied by the test");
            }
        };
    }

    @Override
    public ObservationController observations() {
        return new ObservationController() {
            @Override
            public Observation observe(TargetHandle target, ObservationOptions options) throws DeviceException {
                if (closed) {
                    throw new DeviceException(DeviceException.Code.UNAVAILABLE, "closed");
                }
                return observation();
            }

            @Override
            public Frame captureFrame(TargetHandle target) throws DeviceException {
                throw new DeviceException(DeviceException.Code.REJECTED, "frame is not used");
            }
        };
    }

    @Override
    public InputController input() {
        return new InputController() {
            @Override
            public InputReceipt tap(TargetHandle target, Observation observation, Point point)
                    throws DeviceException {
                if (!results && point.y() >= 200 && point.y() < 260) {
                    results = true;
                }
                return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
            }

            @Override
            public InputReceipt setText(TargetHandle target, Observation observation,
                    String nodeRef, String value) throws DeviceException {
                text = value;
                return new InputReceipt(InputStatus.VERIFIED, true, true, value, Instant.now());
            }

            @Override
            public InputReceipt swipe(TargetHandle target, Observation observation,
                    Point from, Point to, int durationMs) throws DeviceException {
                return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
            }

            @Override
            public InputReceipt back(TargetHandle target, Observation observation)
                    throws DeviceException {
                results = false;
                return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
            }
        };
    }

    @Override
    public TaskController tasks() {
        return new TaskController() {
            @Override
            public TargetInfo moveToMain(TargetHandle target) {
                throw new UnsupportedOperationException("task move is not used by the loop test");
            }

            @Override
            public TargetInfo moveToPrivate(TargetHandle target, agent.next.device.DisplayHandle display) {
                throw new UnsupportedOperationException("task move is not used by the loop test");
            }
        };
    }

    @Override
    public void close() {
        closed = true;
    }

    private Observation observation() {
        String id = "loop-observation-" + (++observationSequence);
        long sequence = observationSequence;
        List<UiNode> nodes = new ArrayList<>();
        NodeBounds full = new NodeBounds(0, 0, 720, 1280);
        nodes.add(node(id + ":0", 0, -1, 100, "root",
                "android.widget.FrameLayout", "", "",
                full, EnumSet.of(NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED)));
        if (results) {
            nodes.add(node(id + ":1", 1, 0, 100, "result",
                    "android.widget.TextView", "Item added", "",
                    new NodeBounds(20, 300, 700, 360),
                    EnumSet.of(NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED)));
        } else {
            nodes.add(node(id + ":1", 1, 0, 100, "search",
                    "android.widget.EditText", text, "Search",
                    new NodeBounds(20, 100, 700, 160),
                    EnumSet.of(NodeFlag.EDITABLE, NodeFlag.FOCUSED,
                            NodeFlag.VISIBLE_TO_USER, NodeFlag.ENABLED)));
            nodes.add(node(id + ":2", 2, 0, 100, "submit",
                    "android.widget.Button", "Search", "",
                    new NodeBounds(20, 200, 700, 260),
                    EnumSet.of(NodeFlag.CLICKABLE, NodeFlag.VISIBLE_TO_USER,
                            NodeFlag.ENABLED)));
        }
        return new Observation(ObservationStatus.READY,
                new ObservationRef(id, sequence),
                "com.example.shop",
                results ? "fake.Results" : "fake.Main",
                101, 100, true, Instant.now(), nodes.size(), false, full, nodes);
    }

    private static UiNode node(String ref, int index, int parent, int windowId,
            String viewId, String className, String text, String description,
            NodeBounds bounds, EnumSet<NodeFlag> flags) {
        return new UiNode(ref, index, parent, windowId, viewId, className,
                text, description, bounds, flags, false);
    }
}
