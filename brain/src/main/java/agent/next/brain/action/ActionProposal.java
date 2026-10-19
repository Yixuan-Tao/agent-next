package agent.next.brain.action;

import agent.next.brain.ActionFlag;
import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.brain.loop.NodeSelector;

import java.util.Objects;
import java.util.Set;

/**
 * A semantic action proposed by a policy. It must not hold a DeviceApi, shell,
 * callback, or screen coordinate. The trusted grounder/executor resolves it
 * against a fresh observation.
 */
public sealed interface ActionProposal permits ActionProposal.Tap,
        ActionProposal.SetText, ActionProposal.Back, ActionProposal.Swipe,
        ActionProposal.Wait {

    String id();

    String description();

    default ActionKind kind() {
        return ActionKind.NAVIGATE;
    }

    RiskClass proposedRisk();

    default Set<ActionFlag> flags() {
        return Set.of();
    }

    default String payloadHash() {
        return Integer.toHexString((id() + "|" + description()).hashCode());
    }

    record Tap(String id, String description, NodeSelector selector,
            RiskClass proposedRisk) implements ActionProposal {
        public Tap {
            id = requireText(id, "id");
            description = requireText(description, "description");
            selector = Objects.requireNonNull(selector, "selector");
            proposedRisk = Objects.requireNonNull(proposedRisk, "proposedRisk");
        }

        @Override
        public String payloadHash() {
            return Integer.toHexString((id + "|" + selector.description()).hashCode());
        }
    }

    record SetText(String id, String description, NodeSelector selector, String text,
            RiskClass proposedRisk) implements ActionProposal {
        public SetText {
            id = requireText(id, "id");
            description = requireText(description, "description");
            selector = Objects.requireNonNull(selector, "selector");
            text = requireText(text, "text");
            proposedRisk = Objects.requireNonNull(proposedRisk, "proposedRisk");
        }

        @Override
        public ActionKind kind() {
            return ActionKind.REVERSIBLE_WRITE;
        }

        @Override
        public String payloadHash() {
            return Integer.toHexString((id + "|" + selector.description() + "|" + text).hashCode());
        }
    }

    record Back(String id, String description, RiskClass proposedRisk)
            implements ActionProposal {
        public Back {
            id = requireText(id, "id");
            description = requireText(description, "description");
            proposedRisk = Objects.requireNonNull(proposedRisk, "proposedRisk");
        }
    }

    record Swipe(String id, String description, Direction direction, int durationMs,
            RiskClass proposedRisk) implements ActionProposal {
        public Swipe {
            id = requireText(id, "id");
            description = requireText(description, "description");
            direction = Objects.requireNonNull(direction, "direction");
            proposedRisk = Objects.requireNonNull(proposedRisk, "proposedRisk");
            if (durationMs < 100 || durationMs > 2000) {
                throw new IllegalArgumentException("durationMs must be 100..2000");
            }
        }
    }

    record Wait(String id, String description, long millis) implements ActionProposal {
        public Wait {
            id = requireText(id, "id");
            description = requireText(description, "description");
            if (millis < 100 || millis > 5000) {
                throw new IllegalArgumentException("wait must be 100..5000ms");
            }
        }

        @Override
        public RiskClass proposedRisk() {
            return RiskClass.LOW;
        }
    }

    enum Direction {
        UP, DOWN, LEFT, RIGHT
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
