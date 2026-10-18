package agent.next.brain.loop;

/** Thrown by a loop control point when the user has stopped the task. */
public final class LoopStoppedException extends Exception {

    private static final long serialVersionUID = 1L;

    public LoopStoppedException(String message) {
        super(message);
    }
}
