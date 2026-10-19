package agent.next.brain.contract;

import java.util.List;
import java.util.Objects;

/** Final task-level report; DONE requires every required outcome satisfied. */
public record TaskReport(String taskId, Status status, List<OutcomeResult> outcomes,
        int actions, int attempts, String detail) {

    public enum Status {
        DONE, PARTIAL, NEED_USER, STOPPED, FAILED
    }

    public enum OutcomeState {
        SATISFIED, UNSATISFIED, UNKNOWN
    }

    public record OutcomeResult(String id, String description, OutcomeState state,
            String evidence) {
        public OutcomeResult {
            id = Objects.requireNonNull(id, "id");
            description = Objects.requireNonNull(description, "description");
            state = Objects.requireNonNull(state, "state");
            evidence = evidence == null ? "" : evidence;
        }
    }

    public TaskReport {
        taskId = Objects.requireNonNull(taskId, "taskId");
        status = Objects.requireNonNull(status, "status");
        outcomes = List.copyOf(outcomes);
        detail = detail == null ? "" : detail;
    }

    public boolean done() {
        return status == Status.DONE
                && outcomes.stream().allMatch(o -> o.state() == OutcomeState.SATISFIED);
    }
}
