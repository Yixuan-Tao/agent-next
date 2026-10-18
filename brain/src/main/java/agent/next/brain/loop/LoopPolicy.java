package agent.next.brain.loop;

import java.util.Optional;

/**
 * Decision port for the fast loop. The deterministic test policy and a future
 * model-backed policy implement the same interface.
 */
public interface LoopPolicy {

    Optional<LoopAction> next(LoopContext context);

    /** Optional evidence-based completion check for the current subgoal. */
    default boolean complete(LoopContext context) {
        return false;
    }
}
