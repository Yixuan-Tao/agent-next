package agent.next.brain;

import java.util.Objects;

/** What to try when a subgoal or skill fails. */
public record Fallback(Strategy strategy, String description) {

    public enum Strategy {
        RETRY,
        GO_BACK,
        SCROLL,
        CHANGE_QUERY,
        ASK_USER,
        ABORT
    }

    public Fallback {
        strategy = Objects.requireNonNull(strategy, "strategy");
        description = Preconditions.requireText(description, "description");
    }
}
