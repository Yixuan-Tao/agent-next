package agent.next.brain.session;

import agent.next.brain.loop.LoopResult;

import java.util.Objects;

/** One FastLoop attempt for one subgoal on one target. */
public record SubgoalRunResult(LoopResult result, int attempt) {

    public SubgoalRunResult {
        result = Objects.requireNonNull(result, "result");
        if (attempt <= 0) {
            throw new IllegalArgumentException("attempt must be positive");
        }
    }
}
