package agent.next.brain.loop;

import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

import java.util.Objects;

/** Back navigation bound to the current observation. */
public record BackAction(String id, String description, RiskClass risk) implements LoopAction {

    public BackAction {
        id = requireText(id, "id");
        description = requireText(description, "description");
        risk = Objects.requireNonNull(risk, "risk");
    }

    @Override
    public ActionKind kind() {
        return ActionKind.NAVIGATE;
    }

    @Override
    public String payloadHash() {
        return Hashes.sha256(id, description);
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        return api.input().back(target, observation);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
