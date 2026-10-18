package agent.next.brain.skill;

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

/** A semantic wait; it carries no coordinates and performs no device input. */
final class SkillWaitAction implements LoopAction {

    private final String id;
    private final long millis;

    SkillWaitAction(String id, long millis) {
        this.id = id;
        this.millis = millis;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return "wait for the UI to settle";
    }

    @Override
    public ActionKind kind() {
        return ActionKind.NAVIGATE;
    }

    @Override
    public RiskClass risk() {
        return RiskClass.LOW;
    }

    @Override
    public String payloadHash() {
        return "wait:" + millis;
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new DeviceException(DeviceException.Code.INTERNAL,
                    "wait interrupted", interrupted);
        }
        return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
    }
}
