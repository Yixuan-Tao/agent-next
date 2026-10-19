package agent.next.brain.action;

import agent.next.brain.ConfirmationRequest;
import agent.next.brain.ConfirmationToken;
import agent.next.brain.HumanGateway;
import agent.next.brain.HumanUnavailableException;
import agent.next.brain.RiskDecision;
import agent.next.brain.RiskGate;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.HumanHandoffRequiredException;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.NodeBounds;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.Point;
import agent.next.device.TargetHandle;

import java.time.Instant;
import java.util.Objects;

/**
 * The only device side-effect entry for GUI actions.
 *
 * <p>Flow: cancel check → fresh observe → ground → risk gate → confirmation →
 * re-observe/re-ground (content may have changed) → input → receipt. A stale
 * confirmation or changed payload is rejected, never retried implicitly.</p>
 */
public final class ActionExecutor {

    public record Execution(GroundedAction action, InputReceipt receipt,
            boolean confirmed, Instant at) {
        public Execution {
            action = Objects.requireNonNull(action, "action");
            receipt = Objects.requireNonNull(receipt, "receipt");
            at = Objects.requireNonNull(at, "at");
        }
    }

    private final DeviceApi api;
    private final RiskGate riskGate;
    private final HumanGateway humanGateway;
    private final LoopControl control;
    private final ActionGrounder grounder = new ActionGrounder();

    public ActionExecutor(DeviceApi api, RiskGate riskGate, HumanGateway humanGateway,
            LoopControl control) {
        this.api = Objects.requireNonNull(api, "api");
        this.riskGate = Objects.requireNonNull(riskGate, "riskGate");
        this.humanGateway = Objects.requireNonNull(humanGateway, "humanGateway");
        this.control = Objects.requireNonNull(control, "control");
    }

    public Execution execute(TaskSpec task, TargetHandle target, ActionProposal proposal)
            throws DeviceException, LoopStoppedException, HumanUnavailableException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(proposal, "proposal");
        control.checkpoint();
        Observation before = api.observations().observe(target, ObservationOptions.defaults());
        GroundedAction grounded = grounder.ground(task, target, before, proposal);
        RiskDecision decision = riskGate.evaluate(grounded.descriptor(), task);
        boolean confirmed = false;
        if (decision instanceof RiskDecision.Deny deny) {
            throw new DeviceException(DeviceException.Code.REJECTED, deny.reason());
        }
        if (decision instanceof RiskDecision.Handoff handoff) {
            throw new HumanHandoffRequiredException(handoff.reason());
        }
        if (decision instanceof RiskDecision.Confirm confirm) {
            ConfirmationRequest request = confirm.request();
            ConfirmationToken token = humanGateway.confirm(request);
            token.requireValidFor(request, Instant.now());
            confirmed = true;
        }
        control.checkpoint();

        Observation current = api.observations().observe(target, ObservationOptions.defaults());
        GroundedAction fresh = grounder.ground(task, target, current, proposal);
        if (!fresh.descriptor().id().equals(grounded.descriptor().id())
                || !fresh.descriptor().payloadHash().equals(grounded.descriptor().payloadHash())) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "action target/content changed after confirmation");
        }
        control.checkpoint();
        InputReceipt receipt = act(current, target, fresh);
        return new Execution(fresh, receipt, confirmed, Instant.now());
    }

    private InputReceipt act(Observation observation, TargetHandle target,
            GroundedAction action) throws DeviceException {
        ActionProposal proposal = action.proposal();
        if (proposal instanceof ActionProposal.Tap) {
            if (!(action.target() instanceof GroundedAction.Node node)) {
                throw new DeviceException(DeviceException.Code.INTERNAL, "tap without node");
            }
            NodeBounds bounds = node.bounds();
            return api.input().tap(target, observation,
                    new Point(bounds.centerX(), bounds.centerY()));
        }
        if (proposal instanceof ActionProposal.SetText setText) {
            if (!(action.target() instanceof GroundedAction.Node node)) {
                throw new DeviceException(DeviceException.Code.INTERNAL, "setText without node");
            }
            return api.input().setText(target, observation, node.ref(), setText.text());
        }
        if (proposal instanceof ActionProposal.Back) {
            return api.input().back(target, observation);
        }
        if (proposal instanceof ActionProposal.Swipe swipe) {
            NodeBounds bounds = observation.windowBounds().orElseThrow(() ->
                    new DeviceException(DeviceException.Code.REJECTED,
                            "swipe requires window bounds"));
            Point[] points = swipePoints(bounds, swipe.direction());
            return api.input().swipe(target, observation, points[0], points[1],
                    swipe.durationMs());
        }
        if (proposal instanceof ActionProposal.Wait wait) {
            try {
                Thread.sleep(wait.millis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DeviceException(DeviceException.Code.INTERNAL, "wait interrupted",
                        interrupted);
            }
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }
        throw new DeviceException(DeviceException.Code.INTERNAL, "unsupported action");
    }

    private static Point[] swipePoints(NodeBounds bounds, ActionProposal.Direction direction) {
        int cx = bounds.centerX();
        int cy = bounds.centerY();
        int dx = Math.max(1, bounds.width() / 4);
        int dy = Math.max(1, bounds.height() / 4);
        return switch (direction) {
            case UP -> new Point[] {new Point(cx, cy + dy), new Point(cx, cy - dy)};
            case DOWN -> new Point[] {new Point(cx, cy - dy), new Point(cx, cy + dy)};
            case LEFT -> new Point[] {new Point(cx + dx, cy), new Point(cx - dx, cy)};
            case RIGHT -> new Point[] {new Point(cx - dx, cy), new Point(cx + dx, cy)};
        };
    }
}
