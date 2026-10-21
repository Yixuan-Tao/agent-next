package agent.phone.capability;

import agent.next.device.DeviceApiContract;
import agent.next.device.DeviceException;
import agent.next.device.DisplayHandle;
import agent.next.device.DisplaySpec;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.Point;
import agent.next.device.TargetHandle;
import agent.next.device.TargetSpec;
import agent.next.device.UiNode;
import android.view.Surface;
import agent.phone.connection.FakeConnectionDriver;

/** Host contract run for the synchronous capability adapter. */
public final class CapabilityAdapterHostTest {

    private CapabilityAdapterHostTest() {
    }

    public static void main(String[] args) throws Exception {
        FakeConnectionDriver driver = new FakeConnectionDriver();
        BlockingDeviceApi api = new BlockingDeviceApi(listener ->
                {
                    driver.attach(listener);
                    return driver;
                }, 2_000L);

        api.connect();
        DeviceApiContract.run(api);
        api.close();

        try {
            api.capabilities();
            throw new AssertionError("closed adapter must reject capabilities");
        } catch (DeviceException expected) {
            if (expected.code() != DeviceException.Code.UNAVAILABLE) {
                throw new AssertionError("expected UNAVAILABLE, got " + expected.code());
            }
        }
        api.close();
        mainScreenInputRejected();
        previewAndUserTouch();
        readOnlyMirrorDoesNotOwnInput();
        failedMirrorStopRetainsOwnership();
        launchWaitsForObservation();
        System.out.println("CapabilityAdapterHostTest: PASS (BlockingDeviceApi on FakeConnectionDriver)");
    }

    private static void launchWaitsForObservation() throws Exception {
        FakeConnectionDriver driver = new FakeConnectionDriver();
        driver.intermediateLaunchState = true;
        BlockingDeviceApi api = new BlockingDeviceApi(listener -> {
            driver.attach(listener);
            return driver;
        }, 2_000L);
        api.connect();
        DisplayHandle display = api.displays().create(DisplaySpec.standard());
        api.targets().open(display, TargetSpec.single("launch-race", "com.example.shop"));
        if (!driver.launchFinished) throw new AssertionError("intermediate NONE state cannot complete launch");
        api.close();
    }

    private static void failedMirrorStopRetainsOwnership() throws Exception {
        FakeConnectionDriver driver = new FakeConnectionDriver();
        BlockingDeviceApi api = new BlockingDeviceApi(listener -> {
            driver.attach(listener);
            return driver;
        }, 2_000L);
        api.connect();
        DisplayHandle display = api.displays().create(DisplaySpec.standard());
        TargetHandle target = api.targets().open(display,
                TargetSpec.single("task-stop-mirror", "com.example.shop"));
        api.startPreview(display, new Surface());
        if (!driver.lastPreviewUserControl) throw new AssertionError("interactive preview must request audio takeover");
        driver.rejectPreviewStop = true;
        boolean failed = false;
        try { api.stopPreview(display); }
        catch (Exception expected) { failed = true; }
        if (!failed) throw new AssertionError("unconfirmed stop must report failure");
        Observation observed = api.observations().observe(target, ObservationOptions.defaults());
        try {
            api.input().tap(target, observed, new Point(10, 10));
            throw new AssertionError("unconfirmed takeover stop must retain input ownership");
        } catch (DeviceException expected) {
            if (expected.code() != DeviceException.Code.REJECTED) throw expected;
        }
        driver.rejectPreviewStop = false;
        api.stopPreview(display);
        api.startReadOnlyMirror(display, new Surface());
        if (driver.lastPreviewUserControl) throw new AssertionError("read-only monitoring must not unmute audio");
        driver.rejectPreviewStop = true;
        failed = false;
        try { api.stopReadOnlyMirror(display); }
        catch (Exception expected) { failed = true; }
        if (!failed) throw new AssertionError("unconfirmed read-only stop must report failure");
        try {
            api.startPreview(display, new Surface());
            throw new AssertionError("unconfirmed read-only stop cannot grant a new mirror");
        } catch (DeviceException expected) {
            if (expected.code() != DeviceException.Code.REJECTED) throw expected;
        }
        driver.rejectPreviewStop = false;
        api.stopReadOnlyMirror(display);
        api.close();
    }

    private static void mainScreenInputRejected() throws Exception {
        FakeConnectionDriver driver = new FakeConnectionDriver();
        BlockingDeviceApi api = new BlockingDeviceApi(listener ->
                {
                    driver.attach(listener);
                    return driver;
                }, 2_000L);
        api.connect();
        DisplayHandle display = api.displays().create(DisplaySpec.standard());
        TargetHandle target = api.targets().open(display,
                TargetSpec.single("task-main", "com.example.shop"));
        Observation observation = api.observations().observe(
                target, ObservationOptions.defaults());
        Point point = center(editable(observation));

        api.tasks().moveToMain(target);
        try {
            api.input().tap(target, observation, point);
            throw new AssertionError("input on the main screen must be rejected");
        } catch (DeviceException expected) {
            if (expected.code() != DeviceException.Code.REJECTED) {
                throw new AssertionError("expected REJECTED, got " + expected.code());
            }
        }

        api.tasks().moveToPrivate(target, display);
        Observation restored = api.observations().observe(
                target, ObservationOptions.defaults());
        InputReceipt tap = api.input().tap(target, restored, center(editable(restored)));
        if (tap.status() != InputStatus.INJECTED) {
            throw new AssertionError("input after returning to the private display");
        }
        api.targets().release(target);
        api.displays().release(display);
        api.close();
    }

    private static void previewAndUserTouch() throws Exception {
        FakeConnectionDriver driver = new FakeConnectionDriver();
        BlockingDeviceApi api = new BlockingDeviceApi(listener ->
                {
                    driver.attach(listener);
                    return driver;
                }, 2_000L);
        api.connect();
        DisplayHandle display = api.displays().create(DisplaySpec.standard());
        TargetHandle target = api.targets().open(display,
                TargetSpec.single("task-preview", "com.example.shop"));
        api.startPreview(display, new Surface());
        if (!driver.previewStarted()) {
            throw new AssertionError("preview must be started");
        }
        try {
            api.input().tap(target, api.observations().observe(target,
                    ObservationOptions.defaults()), new Point(10, 10));
            throw new AssertionError("agent input must be blocked while preview is active");
        } catch (DeviceException expected) {
            if (expected.code() != DeviceException.Code.REJECTED) {
                throw new AssertionError("preview ownership rejection code: " + expected.code());
            }
        }
        api.userTap(target, 123, 456);
        if (driver.lastTapX() != 123 || driver.lastTapY() != 456) {
            throw new AssertionError("user tap coordinates");
        }
        api.userSwipe(target, 10, 20, 30, 40, 500);
        if (driver.lastSwipeDuration() != 500) {
            throw new AssertionError("user swipe duration");
        }
        api.stopPreview(display);
        if (driver.previewStarted()) {
            throw new AssertionError("preview must be stopped");
        }
        Observation resumed = api.observations().observe(target, ObservationOptions.defaults());
        InputReceipt afterPreview = api.input().tap(target, resumed, new Point(10, 10));
        if (!afterPreview.accepted()) {
            throw new AssertionError("agent input resumes after preview stops");
        }
        api.targets().release(target);
        api.displays().release(display);
        api.close();
    }

    private static void readOnlyMirrorDoesNotOwnInput() throws Exception {
        FakeConnectionDriver driver = new FakeConnectionDriver();
        BlockingDeviceApi api = new BlockingDeviceApi(listener -> {
            driver.attach(listener);
            return driver;
        }, 2_000L);
        api.connect();
        DisplayHandle display = api.displays().create(DisplaySpec.standard());
        TargetHandle target = api.targets().open(display,
                TargetSpec.single("task-monitor", "com.example.shop"));
        api.startReadOnlyMirror(display, new Surface());
        Observation observed = api.observations().observe(target, ObservationOptions.defaults());
        if (!api.input().tap(target, observed, new Point(10, 10)).accepted()) {
            throw new AssertionError("read-only monitoring must not pause agent input");
        }
        api.stopPreview(display);
        if (!driver.previewStarted()) {
            throw new AssertionError("closing takeover must not stop a read-only mirror");
        }
        try {
            api.startPreview(display, new Surface());
            throw new AssertionError("takeover must explicitly stop the read-only mirror first");
        } catch (DeviceException expected) {
            if (expected.code() != DeviceException.Code.REJECTED) {
                throw new AssertionError("mirror ownership rejection code", expected);
            }
        }
        api.stopReadOnlyMirror(display);
        if (driver.previewStarted()) {
            throw new AssertionError("read-only mirror must stop on request");
        }
        api.startPreview(display, new Surface());
        api.stopReadOnlyMirror(display);
        if (!driver.previewStarted()) {
            throw new AssertionError("closing a monitor must not stop takeover");
        }
        api.stopPreview(display);
        api.targets().release(target);
        api.displays().release(display);
        api.close();
    }

    private static UiNode editable(Observation observation) {
        for (UiNode node : observation.nodes()) {
            if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                return node;
            }
        }
        throw new AssertionError("editable node exists");
    }

    private static Point center(UiNode node) {
        return new Point(node.bounds().centerX(), node.bounds().centerY());
    }
}
