package agent.next.brain.action;

import agent.next.brain.ActionDescriptor;
import agent.next.device.NodeBounds;
import agent.next.device.ObservationRef;

import java.util.Objects;

/**
 * A proposal bound to one task, target, observation version and resolved
 * concrete element. Coordinates and observation ids never come from the model.
 */
public record GroundedAction(
        String taskId,
        String targetPackage,
        String targetToken,
        ObservationRef observation,
        ActionProposal proposal,
        GroundedTarget target,
        ActionDescriptor descriptor) {

    public GroundedAction {
        taskId = text(taskId, "taskId");
        targetPackage = text(targetPackage, "targetPackage");
        targetToken = text(targetToken, "targetToken");
        observation = Objects.requireNonNull(observation, "observation");
        proposal = Objects.requireNonNull(proposal, "proposal");
        target = Objects.requireNonNull(target, "target");
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    public sealed interface GroundedTarget permits Node, None {
    }

    public record Node(String ref, NodeBounds bounds) implements GroundedTarget {
        public Node {
            ref = text(ref, "ref");
            bounds = Objects.requireNonNull(bounds, "bounds");
            if (bounds.width() <= 0 || bounds.height() <= 0) {
                throw new IllegalArgumentException("target bounds must be non-empty");
            }
        }
    }

    public record None() implements GroundedTarget {
    }

    private static String text(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
