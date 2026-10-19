package agent.next.app.skill;

import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.brain.loop.LoopAction;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.Observation;
import agent.next.device.Point;
import agent.next.device.TargetHandle;

import java.time.Instant;

/** Low-risk tap at a point derived from an accessible node. */
public final class TapPointAction implements LoopAction {

    private final Point point;

    public TapPointAction(Point point) {
        this.point = point;
    }

    @Override
    public String id() {
        return "tap.point." + point.x() + "." + point.y();
    }

    @Override
    public String description() {
        return "tap point " + point.x() + "," + point.y();
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
        return Integer.toHexString(point.x() * 31 + point.y());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        return api.input().tap(target, observation, point);
    }
}
