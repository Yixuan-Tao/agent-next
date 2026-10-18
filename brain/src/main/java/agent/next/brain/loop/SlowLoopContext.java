package agent.next.brain.loop;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;

import java.util.Objects;
import java.util.Optional;

/** Inputs available to the slow-loop decision policy. */
public record SlowLoopContext(
        TaskSpec task,
        TaskGraph graph,
        Optional<Subgoal> current,
        LoopResult fastResult,
        int attempt,
        WorkingMemory memory) {

    public SlowLoopContext {
        task = Objects.requireNonNull(task, "task");
        graph = Objects.requireNonNull(graph, "graph");
        current = Objects.requireNonNull(current, "current");
        fastResult = Objects.requireNonNull(fastResult, "fastResult");
        if (fastResult.status() != LoopResult.Status.SLOW_LOOP) {
            throw new IllegalArgumentException("slow loop requires a SLOW_LOOP result");
        }
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        memory = Objects.requireNonNull(memory, "memory");
    }
}
