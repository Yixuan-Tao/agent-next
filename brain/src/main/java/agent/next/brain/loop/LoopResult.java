package agent.next.brain.loop;

import agent.next.brain.SlowLoopReason;
import agent.next.device.Observation;

import java.util.Optional;

/** Outcome of one fast-loop run. */
public record LoopResult(
        Status status,
        String detail,
        SlowLoopReason slowLoopReason,
        int actions,
        Observation lastObservation) {

    public enum Status {
        DONE,
        NEED_USER,
        SLOW_LOOP,
        BUDGET_EXHAUSTED,
        FAILED
    }

    public LoopResult {
        status = java.util.Objects.requireNonNull(status, "status");
        detail = java.util.Objects.requireNonNull(detail, "detail");
        if (actions < 0) {
            throw new IllegalArgumentException("actions must not be negative");
        }
        if (status == Status.SLOW_LOOP && slowLoopReason == null) {
            throw new IllegalArgumentException("slow-loop result requires a reason");
        }
        if (status != Status.SLOW_LOOP && slowLoopReason != null) {
            throw new IllegalArgumentException("only slow-loop results carry a reason");
        }
    }

    public Optional<SlowLoopReason> reason() {
        return Optional.ofNullable(slowLoopReason);
    }

    public static LoopResult done(int actions, Observation observation) {
        return new LoopResult(Status.DONE, "goal complete", null, actions, observation);
    }

    public static LoopResult needUser(String detail, int actions, Observation observation) {
        return new LoopResult(Status.NEED_USER, detail, null, actions, observation);
    }

    public static LoopResult slowLoop(SlowLoopReason reason, String detail,
            int actions, Observation observation) {
        return new LoopResult(Status.SLOW_LOOP, detail, reason, actions, observation);
    }

    public static LoopResult budgetExhausted(String detail, int actions, Observation observation) {
        return new LoopResult(Status.BUDGET_EXHAUSTED, detail, null, actions, observation);
    }

    public static LoopResult failed(String detail, int actions, Observation observation) {
        return new LoopResult(Status.FAILED, detail, null, actions, observation);
    }
}
