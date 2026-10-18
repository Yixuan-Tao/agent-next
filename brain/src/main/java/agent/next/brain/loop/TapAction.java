package agent.next.brain.loop;

import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.Observation;
import agent.next.device.Point;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;

import java.util.Objects;

/** Tap the center of the first node matching a selector. */
public record TapAction(String id, String description, NodeSelector selector, RiskClass risk)
        implements LoopAction {

    public TapAction {
        id = requireText(id, "id");
        description = requireText(description, "description");
        selector = Objects.requireNonNull(selector, "selector");
        risk = Objects.requireNonNull(risk, "risk");
    }

    @Override
    public ActionKind kind() {
        return ActionKind.NAVIGATE;
    }

    @Override
    public String payloadHash() {
        return Hashes.sha256(id, description, selector.description());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        UiNode node = selector.find(observation).orElseThrow(() -> new DeviceException(
                DeviceException.Code.REJECTED, "tap target not found: " + selector.description()));
        Point point = new Point(node.bounds().centerX(), node.bounds().centerY());
        return api.input().tap(target, observation, point);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
