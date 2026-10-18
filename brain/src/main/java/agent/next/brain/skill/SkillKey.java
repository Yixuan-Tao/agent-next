package agent.next.brain.skill;

import java.util.Objects;

/** Immutable identity of one skill version. */
public record SkillKey(String id, String version) {

    public SkillKey {
        id = Checks.text(id, "id");
        version = Checks.text(version, "version");
    }

    public static SkillKey of(SkillPackage skill) {
        Objects.requireNonNull(skill, "skill");
        return new SkillKey(skill.id(), skill.version());
    }
}
