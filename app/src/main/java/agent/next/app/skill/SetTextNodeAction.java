package agent.next.app.skill;

import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.brain.loop.LoopAction;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

/** Set text on an observed node reference; observation is consumed by the device API. */
public final class SetTextNodeAction implements LoopAction {

    private final String nodeRef;
    private final String text;

    public SetTextNodeAction(String nodeRef, String text) {
        this.nodeRef = nodeRef;
        this.text = text;
    }

    @Override
    public String id() {
        return "settext." + Integer.toHexString(nodeRef.hashCode());
    }

    @Override
    public String description() {
        return "set text on " + nodeRef;
    }

    @Override
    public ActionKind kind() {
        return ActionKind.REVERSIBLE_WRITE;
    }

    @Override
    public RiskClass risk() {
        return RiskClass.LOW;
    }

    @Override
    public String payloadHash() {
        return Integer.toHexString(text.hashCode());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        return api.input().setText(target, observation, nodeRef, text);
    }
}
