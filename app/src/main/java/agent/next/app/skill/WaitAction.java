package agent.next.app.skill;

import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.brain.loop.LoopAction;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

import java.time.Instant;

/** Bounded observation wait; used after search submission to let async pages settle. */
public final class WaitAction implements LoopAction {

    private final int millis;

    public WaitAction(int millis) {
        if (millis <= 0 || millis > 3000) {
            throw new IllegalArgumentException("wait must be 1..3000ms");
        }
        this.millis = millis;
    }

    @Override
    public String id() {
        return "wait." + millis;
    }

    @Override
    public String description() {
        return "wait " + millis + "ms for page load";
    }

    @Override
    public ActionKind kind() {
        return ActionKind.OBSERVE;
    }

    @Override
    public RiskClass risk() {
        return RiskClass.LOW;
    }

    @Override
    public String payloadHash() {
        return Integer.toHexString(millis);
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeviceException(DeviceException.Code.UNAVAILABLE, "wait interrupted");
        }
        return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
    }
}
