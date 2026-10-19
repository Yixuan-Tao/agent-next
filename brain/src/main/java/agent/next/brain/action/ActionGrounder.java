package agent.next.brain.action;

import agent.next.brain.ActionDescriptor;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.NodeSelector;
import agent.next.device.DeviceException;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves a semantic proposal against one ready observation. It never guesses:
 * zero matches is STALE, multiple matches is REJECTED. The result is the only
 * object the executor may act on.
 */
public final class ActionGrounder {

    public GroundedAction ground(TaskSpec task, TargetHandle target,
            Observation observation, ActionProposal proposal) throws DeviceException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(proposal, "proposal");
        if (observation.reference().isEmpty()) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "observation is not actionable");
        }
        if (!target.packageName().equals(observation.packageName())) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "observation package does not match target");
        }
        GroundedAction.GroundedTarget grounded;
        if (proposal instanceof ActionProposal.Tap tap) {
            grounded = node(observation, tap.selector(), NodeFlag.CLICKABLE);
        } else if (proposal instanceof ActionProposal.SetText setText) {
            UiNode selected = find(observation, setText.selector());
            if (!selected.hasFlag(NodeFlag.EDITABLE)) {
                throw new DeviceException(DeviceException.Code.REJECTED,
                        "text target is not editable");
            }
            if (selected.hasFlag(NodeFlag.PASSWORD)) {
                throw new DeviceException(DeviceException.Code.REJECTED,
                        "text target is a password field");
            }
            grounded = new GroundedAction.Node(selected.ref(), selected.bounds());
        } else {
            grounded = new GroundedAction.None();
        }
        ActionDescriptor descriptor = GuiRiskClassifier.raise(new ActionDescriptor(
                proposal.id(), proposal.description(), proposal.kind(),
                proposal.proposedRisk(), target.packageName(), proposal.payloadHash(),
                proposal.flags()));
        return new GroundedAction(target.taskId(), target.packageName(), target.token(),
                observation.reference().orElseThrow(), proposal, grounded, descriptor);
    }

    private static GroundedAction.Node node(Observation observation, NodeSelector selector,
            NodeFlag required) throws DeviceException {
        UiNode selected = find(observation, selector);
        if (!selected.hasFlag(required)) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "selected node lacks " + required);
        }
        return new GroundedAction.Node(selected.ref(), selected.bounds());
    }

    private static UiNode find(Observation observation, NodeSelector selector)
            throws DeviceException {
        List<UiNode> matches = new ArrayList<>();
        for (UiNode node : observation.nodes()) {
            if (selector.matches(node)) {
                matches.add(node);
            }
        }
        if (matches.isEmpty()) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "no node matches: " + selector.description());
        }
        if (matches.size() > 1) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "ambiguous node selector: " + selector.description());
        }
        return matches.get(0);
    }
}
