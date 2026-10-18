package agent.next.brain.skill;

import agent.next.brain.RiskClass;

import java.util.Objects;

/** One semantic operation in a skill workflow. No operation accepts a screen point. */
public sealed interface SkillOperation
        permits SkillOperation.Tap, SkillOperation.SetText, SkillOperation.Back {

    SkillCapability capability();

    String description();

    RiskClass risk();

    record Tap(SkillSelector selector, RiskClass risk) implements SkillOperation {
        public Tap {
            selector = Objects.requireNonNull(selector, "selector");
            risk = Objects.requireNonNull(risk, "risk");
        }

        @Override
        public SkillCapability capability() {
            return SkillCapability.TAP;
        }

        @Override
        public String description() {
            return "tap " + selector.description();
        }
    }

    record SetText(SkillSelector selector, String textTemplate, RiskClass risk)
            implements SkillOperation {
        public SetText {
            selector = Objects.requireNonNull(selector, "selector");
            textTemplate = Checks.text(textTemplate, "textTemplate");
            risk = Objects.requireNonNull(risk, "risk");
        }

        @Override
        public SkillCapability capability() {
            return SkillCapability.SET_TEXT;
        }

        @Override
        public String description() {
            return "set text on " + selector.description();
        }
    }

    record Back(RiskClass risk) implements SkillOperation {
        public Back {
            risk = Objects.requireNonNull(risk, "risk");
        }

        @Override
        public SkillCapability capability() {
            return SkillCapability.BACK;
        }

        @Override
        public String description() {
            return "navigate back";
        }
    }
}
