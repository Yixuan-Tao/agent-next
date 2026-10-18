package agent.next.brain.skill;

/** Validation port for community and self-generated skill admission. */
@FunctionalInterface
public interface SkillValidator {

    SkillValidation validate(SkillPackage skill);
}
