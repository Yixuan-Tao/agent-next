package agent.next.brain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** In-memory episodic store; persistence and retention enforcement come later. */
public final class InMemoryEpisodicMemory implements EpisodicMemory {

    private final List<TaskEvent> events = new ArrayList<>();

    @Override
    public synchronized void append(TaskEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
    }

    @Override
    public synchronized List<TaskEvent> events(String taskId) {
        Preconditions.requireText(taskId, "taskId");
        List<TaskEvent> matches = new ArrayList<>();
        for (TaskEvent event : events) {
            if (event.taskId().equals(taskId)) {
                matches.add(event);
            }
        }
        return List.copyOf(matches);
    }

    @Override
    public synchronized void deleteTask(String taskId) {
        Preconditions.requireText(taskId, "taskId");
        events.removeIf(event -> event.taskId().equals(taskId));
    }
}
