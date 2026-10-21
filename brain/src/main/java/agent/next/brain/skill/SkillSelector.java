package agent.next.brain.skill;

import agent.next.brain.loop.NodeSelector;
import agent.next.device.NodeFlag;
import agent.next.device.UiNode;

import java.util.Objects;

/**
 * Closed selector vocabulary for skills. It contains no coordinates: a skill
 * can only describe the semantic node it wants, never a screen position.
 */
public sealed interface SkillSelector extends NodeSelector
        permits SkillSelector.ViewIdContains, SkillSelector.TextContains,
        SkillSelector.ClickableTextContains, SkillSelector.ClickableContentDescriptionContains,
        SkillSelector.Editable, SkillSelector.WebTextContains {

    record ViewIdContains(String value) implements SkillSelector {
        public ViewIdContains {
            value = Checks.text(value, "viewId");
        }

        @Override
        public boolean matches(UiNode node) {
            return node.viewId().contains(value);
        }

        @Override
        public String description() {
            return "viewId contains " + value;
        }
    }

    record TextContains(String value) implements SkillSelector {
        public TextContains {
            value = Checks.text(value, "text");
        }

        @Override
        public boolean matches(UiNode node) {
            return node.text().contains(value);
        }

        @Override
        public String description() {
            return "text contains " + value;
        }
    }

    record ClickableTextContains(String value) implements SkillSelector {
        public ClickableTextContains {
            value = Checks.text(value, "text");
        }

        @Override
        public boolean matches(UiNode node) {
            return node.hasFlag(NodeFlag.CLICKABLE) && node.text().contains(value);
        }

        @Override
        public String description() {
            return "clickable text contains " + value;
        }
    }

    record Editable() implements SkillSelector {
        @Override
        public boolean matches(UiNode node) {
            return node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD);
        }

        @Override
        public String description() {
            return "editable node";
        }
    }

    record WebTextContains(String value) implements SkillSelector {
        public WebTextContains {
            value = Checks.text(value, "web text");
            if (value.length() > 120) {
                throw new IllegalArgumentException("web text exceeds 120 characters");
            }
        }

        @Override
        public boolean matches(UiNode node) {
            return node.hasFlag(NodeFlag.VISIBLE_TO_USER) && node.hasFlag(NodeFlag.ENABLED)
                    && !node.hasFlag(NodeFlag.PASSWORD) && !node.hasFlag(NodeFlag.EDITABLE)
                    && !node.textTruncated() && !node.text().isBlank()
                    && node.text().contains(value);
        }

        @Override
        public String description() {
            return "web text contains " + value;
        }
    }

    record ClickableContentDescriptionContains(String value) implements SkillSelector {
        public ClickableContentDescriptionContains {
            value = Checks.text(value, "contentDescription");
        }

        @Override
        public boolean matches(UiNode node) {
            return node.hasFlag(NodeFlag.CLICKABLE) && node.contentDescription().contains(value);
        }

        @Override
        public String description() {
            return "clickable contentDescription contains " + value;
        }
    }

    static SkillSelector viewIdContains(String value) {
        return new ViewIdContains(Objects.requireNonNull(value, "value"));
    }

    static SkillSelector textContains(String value) {
        return new TextContains(Objects.requireNonNull(value, "value"));
    }

    static SkillSelector clickableTextContains(String value) {
        return new ClickableTextContains(Objects.requireNonNull(value, "value"));
    }

    static SkillSelector editable() {
        return new Editable();
    }

    static SkillSelector clickableContentDescriptionContains(String value) {
        return new ClickableContentDescriptionContains(Objects.requireNonNull(value, "value"));
    }
}
