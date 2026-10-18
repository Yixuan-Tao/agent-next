package agent.next.brain.loop;

import java.util.Objects;

/** Outcome of one slow-loop decision. */
public record SlowLoopResult(Status status, String detail) {

    public enum Status {
        CONTINUE,
        REPLANNED,
        NEED_USER,
        STOPPED,
        FAILED
    }

    public SlowLoopResult {
        status = Objects.requireNonNull(status, "status");
        detail = Objects.requireNonNull(detail, "detail");
    }

    public static SlowLoopResult continueWith(String detail) {
        return new SlowLoopResult(Status.CONTINUE, detail);
    }

    public static SlowLoopResult replanned(String detail) {
        return new SlowLoopResult(Status.REPLANNED, detail);
    }

    public static SlowLoopResult needUser(String detail) {
        return new SlowLoopResult(Status.NEED_USER, detail);
    }

    public static SlowLoopResult stopped(String detail) {
        return new SlowLoopResult(Status.STOPPED, detail);
    }

    public static SlowLoopResult failed(String detail) {
        return new SlowLoopResult(Status.FAILED, detail);
    }
}
