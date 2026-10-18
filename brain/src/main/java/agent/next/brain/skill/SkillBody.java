package agent.next.brain.skill;

import java.util.List;
import java.util.Objects;

/** Skill forms: deterministic workflow, prompt-only, or workflow plus prompt fallback. */
public sealed interface SkillBody
        permits SkillBody.Workflow, SkillBody.Prompt, SkillBody.Hybrid {

    record Workflow(List<SkillOperation> operations) implements SkillBody {
        public Workflow {
            Objects.requireNonNull(operations, "operations");
            operations = List.copyOf(operations);
            if (operations.isEmpty()) {
                throw new IllegalArgumentException("workflow must have at least one operation");
            }
        }
    }

    record Prompt(String prompt) implements SkillBody {
        public Prompt {
            prompt = Checks.text(prompt, "prompt");
        }
    }

    record Hybrid(List<SkillOperation> operations, String prompt) implements SkillBody {
        public Hybrid {
            Objects.requireNonNull(operations, "operations");
            operations = List.copyOf(operations);
            if (operations.isEmpty()) {
                throw new IllegalArgumentException("hybrid workflow must have at least one operation");
            }
            prompt = Checks.text(prompt, "prompt");
        }
    }
}
