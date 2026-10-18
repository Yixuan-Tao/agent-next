package agent.next.brain.skill;

import java.util.Objects;

/** Result of validating a candidate or community skill artifact. */
public record SkillValidation(boolean passed, String report) {

    public SkillValidation {
        report = Objects.requireNonNull(report, "report");
    }
}
