package agent.next.brain.loop;

import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;

import java.util.Objects;

/** Replace text in the first editable, non-password node matching a selector. */
public record SetTextAction(String id, String description, NodeSelector selector,
        String text, RiskClass risk) implements LoopAction {

    public SetTextAction {
        id = requireText(id, "id");
        description = requireText(description, "description");
        selector = Objects.requireNonNull(selector, "selector");
        text = Objects.requireNonNull(text, "text");
        if (text.isEmpty() || text.length() > 120
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("text must be 1..120 characters without newlines");
        }
        risk = Objects.requireNonNull(risk, "risk");
    }

    @Override
    public ActionKind kind() {
        return ActionKind.REVERSIBLE_WRITE;
    }

    @Override
    public String payloadHash() {
        return Hashes.sha256(id, description, selector.description(), text);
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        UiNode node = selector.find(observation).orElseThrow(() -> new DeviceException(
                DeviceException.Code.REJECTED, "text target not found: " + selector.description()));
        if (!node.hasFlag(NodeFlag.EDITABLE) || node.hasFlag(NodeFlag.PASSWORD)) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "text target is not a non-password editable field");
        }
        return api.input().setText(target, observation, node.ref(), text);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
