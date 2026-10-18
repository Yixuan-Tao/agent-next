package agent.next.brain.loop;

import agent.next.brain.TaskGraph;

import java.util.Objects;

/** Final fast-loop result plus the slow-loop accounting for this run. */
public record LoopRunResult(LoopResult result, int attempts, int replans, TaskGraph graph) {

    public LoopRunResult {
        result = Objects.requireNonNull(result, "result");
        graph = Objects.requireNonNull(graph, "graph");
        if (attempts <= 0 || replans < 0) {
            throw new IllegalArgumentException("invalid attempt counters");
        }
    }
}
