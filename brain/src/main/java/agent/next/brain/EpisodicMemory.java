package agent.next.brain;

import java.util.List;

/** Per-task event history; retention and deletion are policy-driven. */
public interface EpisodicMemory {

    void append(TaskEvent event);

    List<TaskEvent> events(String taskId);

    void deleteTask(String taskId);
}
