package agent.next.brain.loop;

import agent.next.brain.TaskGraph;

import java.util.Objects;

/** Final fast-loop result plus the slow-loop and cross-attempt budget accounting. */
public record LoopRunResult(LoopResult result, int attempts, int replans,
        int totalActions, TaskGraph graph) {

    public LoopRunResult {
        result = Objects.requireNonNull(result, "result");
        graph = Objects.requireNonNull(graph, "graph");
        if (attempts <= 0 || replans < 0 || totalActions < 0) {
            throw new IllegalArgumentException("invalid run counters");
        }
    }
}
