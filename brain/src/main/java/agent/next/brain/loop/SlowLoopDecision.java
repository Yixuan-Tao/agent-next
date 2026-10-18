package agent.next.brain.loop;

import agent.next.brain.Subgoal;

import java.util.Objects;

/** What the slow loop decides after a fast-loop escalation. */
public sealed interface SlowLoopDecision
        permits SlowLoopDecision.Continue, SlowLoopDecision.Replace,
        SlowLoopDecision.AskUser, SlowLoopDecision.Stop {

    record Continue(String reason) implements SlowLoopDecision {
        public Continue {
            reason = text(reason, "reason");
        }
    }

    /** Keeps the current subgoal id so the graph can replace the approach in place. */
    record Replace(Subgoal replacement, String reason) implements SlowLoopDecision {
        public Replace {
            replacement = Objects.requireNonNull(replacement, "replacement");
            reason = text(reason, "reason");
        }
    }

    record AskUser(String question) implements SlowLoopDecision {
        public AskUser {
            question = text(question, "question");
        }
    }

    record Stop(String reason) implements SlowLoopDecision {
        public Stop {
            reason = text(reason, "reason");
        }
    }

    private static String text(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
