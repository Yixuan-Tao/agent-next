package agent.next.brain.action;

import agent.next.brain.ConfirmationRequest;
import agent.next.brain.ConfirmationToken;
import agent.next.brain.ActionDescriptor;
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
import agent.next.device.Frame;
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
    private final VisualTapResolver visualTapResolver;
    private final ActionGrounder grounder = new ActionGrounder();

    public ActionExecutor(DeviceApi api, RiskGate riskGate, HumanGateway humanGateway,
            LoopControl control) {
        this(api, riskGate, humanGateway, control, null);
    }

    public ActionExecutor(DeviceApi api, RiskGate riskGate, HumanGateway humanGateway,
            LoopControl control, VisualTapResolver visualTapResolver) {
        this.api = Objects.requireNonNull(api, "api");
        this.riskGate = Objects.requireNonNull(riskGate, "riskGate");
        this.humanGateway = Objects.requireNonNull(humanGateway, "humanGateway");
        this.control = Objects.requireNonNull(control, "control");
        this.visualTapResolver = visualTapResolver;
    }

    public Execution execute(TaskSpec task, TargetHandle target, ActionProposal proposal)
            throws DeviceException, LoopStoppedException, HumanUnavailableException {
        return execute(task,target,proposal,() -> { });
    }

    /** Called immediately before device input, after grounding and confirmation have succeeded. */
    public Execution execute(TaskSpec task, TargetHandle target, ActionProposal proposal,Runnable beforeInput)
            throws DeviceException, LoopStoppedException, HumanUnavailableException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(proposal, "proposal");
        Objects.requireNonNull(beforeInput,"beforeInput");
        control.checkpoint();
        Observation before = api.observations().observe(target, ObservationOptions.defaults());
        GroundedAction grounded = grounder.ground(task, target, before, proposal);
        RiskDecision decision = riskGate.evaluate(grounded.descriptor(), task);
        ConfirmationRequest approvedRequest = null;
        ConfirmationToken approval = null;
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
            approvedRequest = request;
            approval = token;
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
        if (approval != null) {
            approval.requireValidFor(approvedRequest, Instant.now());
        }
        if (proposal instanceof ActionProposal.VisualTap visualTap) {
            return executeVisualTap(task, target, current, fresh, visualTap,beforeInput);
        }
        InputReceipt receipt = act(current, target, fresh,beforeInput);
        return new Execution(fresh, receipt, confirmed, Instant.now());
    }

    private Execution executeVisualTap(TaskSpec task, TargetHandle target, Observation before,
            GroundedAction grounded, ActionProposal.VisualTap proposal,Runnable beforeInput)
            throws DeviceException, HumanUnavailableException, LoopStoppedException {
        if (visualTapResolver == null) {
            throw new DeviceException(DeviceException.Code.REJECTED, "visual tap is unavailable");
        }
        Frame frame = api.observations().captureFrame(target);
        VisualTapResolution resolved = visualTapResolver.locate(target, proposal.target(), frame);
        Point point = resolved.point();
        if (resolved.confidence() < 0.8 || point.x() < 0 || point.y() < 0
                || point.x() >= frame.width() || point.y() >= frame.height()) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "visual target confidence or bounds check failed");
        }
        ActionDescriptor reviewed = new ActionDescriptor(grounded.descriptor().id(),
                grounded.descriptor().description() + "\nLocated visual label: " + resolved.label(),
                grounded.descriptor().kind(), grounded.descriptor().risk(),
                grounded.targetPackage(), GroundedAction.fingerprint(java.util.List.of(
                        grounded.descriptor().payloadHash(), resolved.label(),
                        Integer.toString(point.x()), Integer.toString(point.y()))),
                grounded.descriptor().flags());
        RiskDecision resolvedRisk = riskGate.evaluate(GuiRiskClassifier.raise(reviewed), task);
        if (resolvedRisk instanceof RiskDecision.Deny deny) {
            throw new DeviceException(DeviceException.Code.REJECTED, deny.reason());
        }
        if (resolvedRisk instanceof RiskDecision.Handoff handoff) {
            throw new HumanHandoffRequiredException(handoff.reason());
        }
        boolean confirmed = false;
        if (resolvedRisk instanceof RiskDecision.Confirm confirm) {
            ConfirmationRequest request = confirm.request();
            ConfirmationToken token = humanGateway.confirm(request);
            token.requireValidFor(request, Instant.now());
            confirmed = true;
        }
        control.checkpoint();
        Observation current = api.observations().observe(target, ObservationOptions.defaults());
        if (!current.packageName().equals(before.packageName())
                || current.displayId() != before.displayId()
                || current.windowId() != before.windowId()
                || current.windowBounds().isEmpty()
                || point.x() < current.windowBounds().orElseThrow().left()
                || point.x() >= current.windowBounds().orElseThrow().right()
                || point.y() < current.windowBounds().orElseThrow().top()
                || point.y() >= current.windowBounds().orElseThrow().bottom()) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "target changed after visual location");
        }
        beforeInput.run();
        InputReceipt receipt = api.input().tap(target, current, point);
        GroundedAction resolvedAction = new GroundedAction(grounded.taskId(), grounded.targetPackage(),
                grounded.targetToken(), current.reference().orElseThrow(), proposal,
                new GroundedAction.None(), reviewed);
        return new Execution(resolvedAction, receipt, confirmed, Instant.now());
    }

    private InputReceipt act(Observation observation, TargetHandle target,
            GroundedAction action,Runnable beforeInput) throws DeviceException {
        ActionProposal proposal = action.proposal();
        if (proposal instanceof ActionProposal.Tap) {
            if (!(action.target() instanceof GroundedAction.Node node)) {
                throw new DeviceException(DeviceException.Code.INTERNAL, "tap without node");
            }
            NodeBounds bounds = node.bounds();
            beforeInput.run();
            return api.input().tap(target, observation,
                    new Point(bounds.centerX(), bounds.centerY()));
        }
        if (proposal instanceof ActionProposal.SetText setText) {
            if (!(action.target() instanceof GroundedAction.Node node)) {
                throw new DeviceException(DeviceException.Code.INTERNAL, "setText without node");
            }
            beforeInput.run();
            return api.input().setText(target, observation, node.ref(), setText.text());
        }
        if (proposal instanceof ActionProposal.Back) {
            beforeInput.run();
            return api.input().back(target, observation);
        }
        if (proposal instanceof ActionProposal.Swipe swipe) {
            NodeBounds bounds = observation.windowBounds().orElseThrow(() ->
                    new DeviceException(DeviceException.Code.REJECTED,
                            "swipe requires window bounds"));
            Point[] points = swipePoints(bounds, swipe.direction());
            beforeInput.run();
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
