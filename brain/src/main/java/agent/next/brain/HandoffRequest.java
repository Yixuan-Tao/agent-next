package agent.next.brain;

/** Request to hand control to the human for a specific task. */
public record HandoffRequest(String taskId, String reason, HandoffMode mode) {

    public HandoffRequest {
        taskId = Preconditions.requireText(taskId, "taskId");
        reason = Preconditions.requireText(reason, "reason");
        if (mode == null) {
            throw new NullPointerException("mode");
        }
    }
}
