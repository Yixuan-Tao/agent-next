package agent.next.app.clock;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;

import java.util.Optional;

/** One domain insert for an alarm subgoal; completion remains verifier-owned. */
public final class ClockPolicy implements LoopPolicy {

    private final ClockStore store;
    private final ClockStore.AlarmSpec spec;
    private boolean attempted;

    public ClockPolicy(ClockStore store, TaskSpec task, Subgoal subgoal) {
        this.store = store;
        this.spec = ClockStore.specFor(task.goal(), subgoal.intent());
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (store.exists(spec) || attempted) {
            return Optional.empty();
        }
        attempted = true;
        return Optional.of(new ClockInsertAction(store, spec));
    }

    @Override
    public boolean complete(LoopContext context) {
        return false;
    }
}
