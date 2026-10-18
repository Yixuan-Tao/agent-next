package agent.next.brain.session;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.TargetHandle;
import agent.next.device.DeviceException;

import java.util.Objects;
import java.util.Optional;

/** Completion is owned by the subgoal verifier, never by the model policy. */
final class VerifiedLoopPolicy implements LoopPolicy {

    private final LoopPolicy delegate;
    private final SubgoalVerifier verifier;
    private final TaskSpec task;
    private final Subgoal subgoal;
    private final TargetHandle target;

    VerifiedLoopPolicy(LoopPolicy delegate, SubgoalVerifier verifier, TaskSpec task,
            Subgoal subgoal, TargetHandle target) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.task = Objects.requireNonNull(task, "task");
        this.subgoal = Objects.requireNonNull(subgoal, "subgoal");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        return delegate.next(context);
    }

    @Override
    public boolean complete(LoopContext context) {
        try {
            return verifier.verify(task, subgoal, target, context.observation()).satisfied();
        } catch (DeviceException | RuntimeException failure) {
            return false;
        }
    }
}
