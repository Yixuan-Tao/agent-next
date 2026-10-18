package agent.next.brain.loop;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;
import agent.next.device.Observation;

import java.util.Objects;

/** Immutable snapshot handed to the policy before/after one action. */
public record LoopContext(
        TaskSpec task,
        Subgoal subgoal,
        Observation observation,
        int actions,
        int noEffect,
        int noProgress,
        WorkingMemory memory) {

    public LoopContext {
        task = Objects.requireNonNull(task, "task");
        subgoal = Objects.requireNonNull(subgoal, "subgoal");
        observation = Objects.requireNonNull(observation, "observation");
        if (actions < 0 || noEffect < 0 || noProgress < 0) {
            throw new IllegalArgumentException("counters must not be negative");
        }
        memory = Objects.requireNonNull(memory, "memory");
    }
}
