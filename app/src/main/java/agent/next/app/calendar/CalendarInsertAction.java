package agent.next.app.calendar;

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

/** Domain action: create a calendar reminder directly, not via calendar UI. */
public final class CalendarInsertAction implements LoopAction {

    private final CalendarStore store;
    private final CalendarStore.EventSpec spec;
    private final String description;

    public CalendarInsertAction(CalendarStore store, CalendarStore.EventSpec spec,
            String description) {
        this.store = store;
        this.spec = spec;
        this.description = description;
    }

    @Override
    public String id() {
        return "calendar.insert";
    }

    @Override
    public String description() {
        return description;
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
        return Integer.toHexString((spec.title() + spec.startMillis()).hashCode());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        if (!store.insert(spec)) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "calendar insert failed (permission/calendar?)");
        }
        return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
    }

    @Override
    public ActionDescriptor descriptor(String targetApp) {
        return new ActionDescriptor(id(), description, kind(), risk(), targetApp,
                payloadHash(), java.util.Set.of());
    }
}
