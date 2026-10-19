package agent.next.app.skill;

import android.util.Log;

import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.brain.loop.LoopAction;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.InputReceipt;
import agent.next.device.Observation;
import agent.next.device.Point;
import agent.next.device.TargetHandle;

import java.io.IOException;
import java.util.Objects;
import java.util.Set;

/** Vision-grounded tap executed against a fresh frame; no stored coordinates. */
public final class VisualTapAction implements LoopAction {

    private final JdVisualLocator locator;
    private final String target;
    private final String id;
    private final Set<String> allowed;
    private final Set<String> forbidden;

    public VisualTapAction(JdVisualLocator locator, String target, String id,
            Set<String> allowed, Set<String> forbidden) {
        this.locator = locator;
        this.target = target;
        this.id = id;
        this.allowed = Set.copyOf(Objects.requireNonNull(allowed, "allowed"));
        this.forbidden = Set.copyOf(Objects.requireNonNull(forbidden, "forbidden"));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String description() {
        return "visual tap: " + target;
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
        return Integer.toHexString(target.hashCode());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle targetHandle,
            Observation observation) throws DeviceException {
        try {
            Frame frame = api.observations().captureFrame(targetHandle);
            Point point = locator.locate(frame, target, allowed, forbidden, 0.5);
            Log.i("AgentNextRuntime", "visual tap point=" + point + " target=" + target);
            return api.input().tap(targetHandle, observation, point);
        } catch (IOException failure) {
            throw new DeviceException(DeviceException.Code.INTERNAL,
                    "visual locator failed", failure);
        }
    }
}
