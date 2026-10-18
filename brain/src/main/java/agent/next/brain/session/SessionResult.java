package agent.next.brain.session;

import agent.next.brain.loop.LoopResult;

import java.util.Objects;

/** Final session outcome; never claims success without a verified subgoal. */
public record SessionResult(Status status, String detail, int actions, int attempts,
        String subgoalId, LoopResult lastLoopResult) {

    public enum Status {
        DONE,
        NEED_USER,
        SLOW_LOOP,
        BUDGET_EXHAUSTED,
        STOPPED,
        FAILED
    }

    public SessionResult {
        status = Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNull(detail, "detail");
        if (actions < 0 || attempts < 0) {
            throw new IllegalArgumentException("counters must not be negative");
        }
    }

    public static SessionResult done(int actions, int attempts) {
        return new SessionResult(Status.DONE, "all subgoals verified", actions, attempts,
                "", null);
    }

    public static SessionResult needUser(String detail, int actions, int attempts,
            String subgoalId, LoopResult last) {
        return new SessionResult(Status.NEED_USER, detail, actions, attempts, subgoalId, last);
    }

    public static SessionResult slowLoop(String detail, int actions, int attempts,
            String subgoalId, LoopResult last) {
        return new SessionResult(Status.SLOW_LOOP, detail, actions, attempts, subgoalId, last);
    }

    public static SessionResult budget(String detail, int actions, int attempts,
            String subgoalId, LoopResult last) {
        return new SessionResult(Status.BUDGET_EXHAUSTED, detail, actions, attempts,
                subgoalId, last);
    }

    public static SessionResult stopped(String detail, int actions, int attempts,
            String subgoalId, LoopResult last) {
        return new SessionResult(Status.STOPPED, detail, actions, attempts, subgoalId, last);
    }

    public static SessionResult failed(String detail, int actions, int attempts,
            String subgoalId, LoopResult last) {
        return new SessionResult(Status.FAILED, detail, actions, attempts, subgoalId, last);
    }
}
