package agent.next.app.calendar;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;

import java.util.Optional;

/** One domain insert for a calendar subgoal; completion remains verifier-owned. */
public final class CalendarPolicy implements LoopPolicy {

    private final CalendarStore store;
    private final CalendarStore.EventSpec spec;
    private boolean attempted;

    public CalendarPolicy(CalendarStore store, TaskSpec task, Subgoal subgoal) {
        this.store = store;
        this.spec = CalendarStore.specFor(task.goal(), subgoal.intent());
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (store.exists(spec) || attempted) {
            return Optional.empty();
        }
        attempted = true;
        return Optional.of(new CalendarInsertAction(store, spec,
                "create calendar reminder: " + spec.title()));
    }

    @Override
    public boolean complete(LoopContext context) {
        return false;
    }
}
