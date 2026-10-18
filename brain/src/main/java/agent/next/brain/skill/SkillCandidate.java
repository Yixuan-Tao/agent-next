package agent.next.brain.skill;

import java.util.Objects;

/** A proposed skill plus the evidence that motivated it. */
public record SkillCandidate(SkillPackage skill, String evidence) {

    public SkillCandidate {
        skill = Objects.requireNonNull(skill, "skill");
        evidence = Checks.text(evidence, "evidence");
    }
}
