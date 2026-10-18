package agent.next.brain.loop;

/**
 * Pause/stop boundary checked between loop steps. Implementations may block
 * while paused; they must throw when the user stops the task.
 */
public interface LoopControl {

    void checkpoint() throws LoopStoppedException;

    static LoopControl none() {
        return () -> {
        };
    }
}
