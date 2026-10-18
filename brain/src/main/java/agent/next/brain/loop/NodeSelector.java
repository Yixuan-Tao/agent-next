package agent.next.brain.loop;

import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.UiNode;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Resolves a semantic selector against the current observation only. */
public interface NodeSelector {

    boolean matches(UiNode node);

    String description();

    default Optional<UiNode> find(Observation observation) {
        Objects.requireNonNull(observation, "observation");
        for (UiNode node : observation.nodes()) {
            if (matches(node)) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    static NodeSelector editable() {
        return new Selector(node -> node.hasFlag(NodeFlag.EDITABLE)
                && !node.hasFlag(NodeFlag.PASSWORD), "editable");
    }

    static NodeSelector textEquals(String text) {
        String expected = require(text);
        return new Selector(node -> expected.equals(node.text()), "text=" + expected);
    }

    static NodeSelector textContains(String text) {
        String expected = require(text);
        return new Selector(node -> node.text().contains(expected), "text contains " + expected);
    }

    static NodeSelector viewIdContains(String viewId) {
        String expected = require(viewId);
        return new Selector(node -> node.viewId().contains(expected),
                "viewId contains " + expected);
    }

    static NodeSelector clickableTextContains(String text) {
        String expected = require(text);
        return new Selector(node -> node.hasFlag(NodeFlag.CLICKABLE)
                && node.text().contains(expected), "clickable text contains " + expected);
    }

    static NodeSelector viewId(String viewId) {
        String expected = require(viewId);
        return new Selector(node -> expected.equals(node.viewId()), "viewId=" + expected);
    }

    private static String require(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("selector value must not be blank");
        }
        return value;
    }

    record Selector(Predicate<UiNode> predicate, String description) implements NodeSelector {

        public Selector {
            Objects.requireNonNull(predicate, "predicate");
            Objects.requireNonNull(description, "description");
        }

        @Override
        public boolean matches(UiNode node) {
            return predicate.test(node);
        }
    }
}
