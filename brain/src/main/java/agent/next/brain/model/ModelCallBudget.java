package agent.next.brain.model;

import java.util.concurrent.atomic.AtomicInteger;

/** Task-scoped model call cap; retries count as calls. */
public final class ModelCallBudget {

    private final int maxCalls;
    private final AtomicInteger used = new AtomicInteger();

    public ModelCallBudget(int maxCalls) {
        if (maxCalls <= 0) {
            throw new IllegalArgumentException("maxCalls must be positive");
        }
        this.maxCalls = maxCalls;
    }

    public int acquire() throws ModelBudgetExceededException {
        int call = used.incrementAndGet();
        if (call > maxCalls) {
            throw new ModelBudgetExceededException(maxCalls);
        }
        return call;
    }

    public int used() {
        return used.get();
    }

    public int maxCalls() {
        return maxCalls;
    }

    public static ModelCallBudget unlimited() {
        return new ModelCallBudget(Integer.MAX_VALUE);
    }
}
