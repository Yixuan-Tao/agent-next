package agent.next.brain.model;

import java.io.IOException;

/** Raised when a task exhausts its model-call budget. */
public final class ModelBudgetExceededException extends IOException {

    private static final long serialVersionUID = 1L;

    public ModelBudgetExceededException(int maxCalls) {
        super("model call budget exhausted: " + maxCalls);
    }
}
