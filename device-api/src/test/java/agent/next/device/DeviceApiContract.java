package agent.next.device;

/**
 * Behavioural contract for any {@link DeviceApi} implementation. The fake
 * implementation runs it now; the capability adapter must pass the same suite.
 */
public final class DeviceApiContract {

    private DeviceApiContract() {
    }

    public static void run(DeviceApi api) throws Exception {
        TestSupport.check(api != null, "api is required");
        capabilities(api);
        DisplayHandle display = api.displays().create(DisplaySpec.standard());
        DisplayInfo displayInfo = display(api, display);
        TargetHandle target = target(api, display);
        TargetInfo targetInfo = api.targets().state(target);
        TestSupport.check(targetInfo.displayId() == displayInfo.displayId(),
                "target starts on the private display");
        Observation observation = observation(api, target);
        input(api, target, observation);
        frames(api, target);
        taskMove(api, target, display, displayInfo);
        staleHandles(api, target, display, observation);
    }

    private static void capabilities(DeviceApi api) throws DeviceException {
        DeviceCapabilities capabilities = api.capabilities();
        TestSupport.check(capabilities.supported(), "device is supported");
        TestSupport.check(capabilities.apiLevel() == 36, "API level");
        TestSupport.check(capabilities.serviceUid() == 2000, "service UID");
        TestSupport.check(capabilities.has(DeviceFeature.PRIVATE_DISPLAY), "private display feature");
        TestSupport.check(capabilities.has(DeviceFeature.TARGET_OBSERVATION), "observation feature");
        TestSupport.check(capabilities.has(DeviceFeature.DIRECTED_INPUT), "input feature");
        TestSupport.check(capabilities.has(DeviceFeature.TASK_MOVE), "task move feature");
    }

    private static DisplayInfo display(DeviceApi api, DisplayHandle display) throws DeviceException {
        DisplayInfo info = api.displays().check(display);
        TestSupport.check(info.width() == 720, "display width");
        TestSupport.check(info.height() == 1280, "display height");
        TestSupport.check(info.densityDpi() == 240, "display density");
        TestSupport.check(info.ownerUid() == 2000, "display owner");
        TestSupport.check(info.generation() > 0, "display generation");
        return info;
    }

    private static TargetHandle target(DeviceApi api, DisplayHandle display) throws DeviceException {
        TargetHandle target = api.targets().open(display,
                TargetSpec.single("task-1", "com.example.shop"));
        TestSupport.check(target.taskId().equals("task-1"), "target task");
        TestSupport.check(target.packageName().equals("com.example.shop"), "target package");
        return target;
    }

    private static Observation observation(DeviceApi api, TargetHandle target) throws DeviceException {
        Observation observation = api.observations().observe(
                target, ObservationOptions.defaults());
        TestSupport.check(observation.status() == ObservationStatus.READY, "observation ready");
        TestSupport.check(observation.reference().isPresent(), "observation reference");
        TestSupport.check(observation.displayId() >= 0, "observation display");
        TestSupport.check(observation.windowId() >= 0, "observation window");
        TestSupport.check(!observation.nodes().isEmpty(), "observation nodes");
        TestSupport.check(observation.nodes().size() <= ObservationOptions.defaults().maxNodes(),
                "observation node budget");
        return observation;
    }

    private static void input(DeviceApi api, TargetHandle target, Observation observation)
            throws Exception {
        UiNode editable = null;
        UiNode password = null;
        for (UiNode node : observation.nodes()) {
            if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                editable = node;
            }
            if (node.hasFlag(NodeFlag.PASSWORD)) {
                password = node;
            }
        }
        TestSupport.check(editable != null, "editable node exists");
        TestSupport.check(password != null, "password node exists");
        final UiNode editableNode = editable;
        final UiNode passwordNode = password;

        UiNode root = observation.nodes().get(0);
        Point point = new Point(editableNode.bounds().centerX(), editableNode.bounds().centerY());
        InputReceipt tap = api.input().tap(target, observation, point);
        TestSupport.check(tap.status() == InputStatus.INJECTED && tap.accepted(), "tap accepted");

        String text = "短袖T恤 男款 XL";
        InputReceipt setText = api.input().setText(target, observation, editableNode.ref(), text);
        TestSupport.check(setText.status() == InputStatus.VERIFIED, "setText verified");
        TestSupport.check(setText.actualText().orElse("").equals(text), "setText readback");

        expectCode(DeviceException.Code.REJECTED,
                () -> api.input().setText(target, observation, root.ref(), "x"),
                "non-editable node rejected");
        expectCode(DeviceException.Code.REJECTED,
                () -> api.input().setText(target, observation, passwordNode.ref(), "x"),
                "password node rejected");
        expectCode(DeviceException.Code.REJECTED,
                () -> api.input().swipe(target, observation, point, point, 50),
                "short swipe rejected");

        InputReceipt swipe = api.input().swipe(target, observation, point,
                new Point(point.x(), 700), 300);
        TestSupport.check(swipe.status() == InputStatus.INJECTED, "swipe injected");
        InputReceipt back = api.input().back(target, observation);
        TestSupport.check(back.status() == InputStatus.INJECTED, "back injected");
    }

    private static void frames(DeviceApi api, TargetHandle target) throws DeviceException {
        Frame frame = api.observations().captureFrame(target);
        TestSupport.check(frame.width() == 720, "frame width");
        TestSupport.check(frame.height() == 1280, "frame height");
        TestSupport.check(frame.png().length > 0, "frame png");
        TestSupport.check(frame.sequence() > 0, "frame sequence");
    }

    private static void taskMove(DeviceApi api, TargetHandle target, DisplayHandle display,
            DisplayInfo displayInfo) throws DeviceException {
        TargetInfo main = api.tasks().moveToMain(target);
        TestSupport.check(main.displayId() == 0, "moved to main display");
        TestSupport.check(main.foreground(), "main-screen target is foreground");

        TargetInfo back = api.tasks().moveToPrivate(target, display);
        TestSupport.check(back.displayId() == displayInfo.displayId(), "moved back to private display");
    }

    private static void staleHandles(DeviceApi api, TargetHandle target, DisplayHandle display,
            Observation observation) throws Exception {
        api.targets().release(target);
        expectCode(DeviceException.Code.STALE, () -> api.targets().state(target),
                "released target is stale");
        expectCode(DeviceException.Code.STALE,
                () -> api.input().tap(target, observation, new Point(10, 10)),
                "observation is stale after target release");

        api.displays().release(display);
        expectCode(DeviceException.Code.STALE, () -> api.displays().check(display),
                "released display is stale");
    }

    private static void expectCode(DeviceException.Code code, TestSupport.ThrowingRunnable action,
            String message) throws Exception {
        DeviceException failure = TestSupport.expect(DeviceException.class, action, message);
        TestSupport.check(failure.code() == code,
                message + ": expected code " + code + " but was " + failure.code());
    }
}
