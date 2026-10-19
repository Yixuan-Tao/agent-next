package agent.next.app.clock;

import agent.next.brain.ActionDescriptor;
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

/** Domain action: set a Clock alarm through AlarmClock, not via the time picker. */
public final class ClockInsertAction implements LoopAction {

    private final ClockStore store;
    private final ClockStore.AlarmSpec spec;

    public ClockInsertAction(ClockStore store, ClockStore.AlarmSpec spec) {
        this.store = store;
        this.spec = spec;
    }

    @Override
    public String id() {
        return "clock.alarm";
    }

    @Override
    public String description() {
        return "set clock alarm: " + spec.hour() + ":" + String.format("%02d", spec.minute())
                + " " + spec.label();
    }

    @Override
    public ActionKind kind() {
        return ActionKind.REVERSIBLE_WRITE;
    }

    @Override
    public RiskClass risk() {
        return RiskClass.REVERSIBLE_SIDE_EFFECT;
    }

    @Override
    public String payloadHash() {
        return Integer.toHexString((spec.hour() + ":" + spec.minute() + spec.label()).hashCode());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        if (!store.insert(spec)) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "alarm insert failed");
        }
        return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
    }

    @Override
    public ActionDescriptor descriptor(String targetApp) {
        return new ActionDescriptor(id(), description(), kind(), risk(), targetApp,
                payloadHash(), java.util.Set.of());
    }
}
