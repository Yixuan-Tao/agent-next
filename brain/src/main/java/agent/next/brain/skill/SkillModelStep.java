package agent.next.brain.skill;

import agent.next.brain.RiskClass;

import java.util.Objects;

/** Semantic step proposed by a model for a prompt skill. No step can carry a screen point. */
public sealed interface SkillModelStep
        permits SkillModelStep.Tap, SkillModelStep.SetText, SkillModelStep.Back,
        SkillModelStep.Wait, SkillModelStep.Done {

    record Tap(SkillSelector selector, RiskClass risk) implements SkillModelStep {
        public Tap {
            selector = Objects.requireNonNull(selector, "selector");
            risk = Objects.requireNonNull(risk, "risk");
        }
    }

    record SetText(SkillSelector selector, String text, RiskClass risk)
            implements SkillModelStep {
        public SetText {
            selector = Objects.requireNonNull(selector, "selector");
            text = Checks.text(text, "text");
            risk = Objects.requireNonNull(risk, "risk");
        }
    }

    record Back(RiskClass risk) implements SkillModelStep {
        public Back {
            risk = Objects.requireNonNull(risk, "risk");
        }
    }

    record Wait(long millis) implements SkillModelStep {
        public Wait {
            if (millis < 100 || millis > 5000) {
                throw new IllegalArgumentException("wait must be 100..5000ms");
            }
        }
    }

    record Done(String reason) implements SkillModelStep {
        public Done {
            reason = Checks.text(reason, "reason");
        }
    }
}
