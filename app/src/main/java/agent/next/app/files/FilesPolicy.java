package agent.next.app.files;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;

import java.util.Optional;

/** One domain insert for a folder subgoal; completion remains verifier-owned. */
public final class FilesPolicy implements LoopPolicy {

    private final FilesStore store;
    private final FilesStore.FolderSpec spec;
    private boolean attempted;

    public FilesPolicy(FilesStore store, TaskSpec task, Subgoal subgoal) {
        this.store = store;
        this.spec = FilesStore.specFor(task.goal(), subgoal.intent());
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (store.exists(spec) || attempted) {
            return Optional.empty();
        }
        attempted = true;
        return Optional.of(new FilesInsertAction(store, spec));
    }

    @Override
    public boolean complete(LoopContext context) {
        return false;
    }
}
