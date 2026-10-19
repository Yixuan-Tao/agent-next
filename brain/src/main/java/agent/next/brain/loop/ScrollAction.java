package agent.next.brain.loop;

import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.NodeBounds;
import agent.next.device.Observation;
import agent.next.device.Point;
import agent.next.device.TargetHandle;

import java.util.Objects;

/**
 * Generic viewport scroll, derived from the current window bounds rather than
 * model-supplied coordinates. Used only as a bounded observe/read fallback.
 */
public record ScrollAction(String id, String description, Direction direction, RiskClass risk)
        implements LoopAction, ProposalCarrier {

    public enum Direction {
        DOWN,
        UP
    }

    public ScrollAction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        direction = Objects.requireNonNull(direction, "direction");
        risk = Objects.requireNonNull(risk, "risk");
    }

    @Override
    public agent.next.brain.action.ActionProposal proposal() {
        agent.next.brain.action.ActionProposal.Direction mapped =
                direction == Direction.DOWN
                        ? agent.next.brain.action.ActionProposal.Direction.DOWN
                        : agent.next.brain.action.ActionProposal.Direction.UP;
        return new agent.next.brain.action.ActionProposal.Swipe(id, description, mapped, 500, risk);
    }

    @Override
    public ActionKind kind() {
        return ActionKind.NAVIGATE;
    }

    @Override
    public String payloadHash() {
        return Hashes.sha256(id, description, direction.name());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        NodeBounds window = observation.windowBounds().orElseThrow(() -> new DeviceException(
                DeviceException.Code.REJECTED, "scroll requires window bounds"));
        int x = window.centerX();
        int yLow = window.top() + window.height() * 3 / 4;
        int yHigh = window.top() + window.height() / 4;
        Point from = direction == Direction.DOWN ? new Point(x, yLow) : new Point(x, yHigh);
        Point to = direction == Direction.DOWN ? new Point(x, yHigh) : new Point(x, yLow);
        return api.input().swipe(target, observation, from, to, 500);
    }
}
