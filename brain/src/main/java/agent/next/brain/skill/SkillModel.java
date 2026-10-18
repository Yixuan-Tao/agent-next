package agent.next.brain.skill;

/** Model port for prompt skills; implement with any model or deterministic fake. */
@FunctionalInterface
public interface SkillModel {

    SkillModelStep next(SkillModelRequest request);
}
