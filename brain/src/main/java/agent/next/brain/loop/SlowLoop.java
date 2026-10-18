package agent.next.brain.loop;

import agent.next.brain.Subgoal;

import java.util.Objects;

/**
 * Applies one slow-loop policy decision to the task graph.
 *
 * <p>Replacement is intentionally in-place: the replacement must keep the
 * current subgoal id. Adding/removing arbitrary nodes is a later replanning
 * capability.</p>
 */
public final class SlowLoop {

    private final SlowLoopPolicy policy;

    public SlowLoop(SlowLoopPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public SlowLoopResult run(SlowLoopContext context) {
        SlowLoopDecision decision = policy.decide(context);
        if (decision == null) {
            return SlowLoopResult.failed("slow-loop policy returned no decision");
        }
        if (decision instanceof SlowLoopDecision.Continue) {
            return SlowLoopResult.continueWith("continue with the current graph");
        }
        if (decision instanceof SlowLoopDecision.AskUser ask) {
            return SlowLoopResult.needUser(ask.question());
        }
        if (decision instanceof SlowLoopDecision.Stop stop) {
            return SlowLoopResult.stopped(stop.reason());
        }
        SlowLoopDecision.Replace replace = (SlowLoopDecision.Replace) decision;
        Subgoal current = context.current().orElse(null);
        if (current == null) {
            return SlowLoopResult.failed("no current subgoal to replace");
        }
        if (!current.id().equals(replace.replacement().id())) {
            return SlowLoopResult.failed(
                    "replacement must keep subgoal id " + current.id());
        }
        try {
            context.graph().replace(replace.replacement());
        } catch (RuntimeException failure) {
            return SlowLoopResult.failed("replace failed: " + failure.getMessage());
        }
        return SlowLoopResult.replanned(
                "replaced approach for " + current.id() + ": " + replace.reason());
    }
}
