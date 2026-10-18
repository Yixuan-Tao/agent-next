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
        System.out.println("CapabilityAdapterHostTest: PASS (BlockingDeviceApi on FakeConnectionDriver)");
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
