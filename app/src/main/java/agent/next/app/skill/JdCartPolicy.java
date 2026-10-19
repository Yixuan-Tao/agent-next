package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;

import java.util.Optional;

/**
 * JD cart navigation is deterministic: the scoped prelude taps the textless tab,
 * then this policy only waits for JdCartVerifier. No model text-selector attempts.
 */
public final class JdCartPolicy implements LoopPolicy {

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        return Optional.of(new WaitAction(1000));
    }
}
