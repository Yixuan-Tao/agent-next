package agent.next.brain.session;

import agent.next.brain.RiskClass;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.ScrollAction;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * If the model reports no next action (Done) while the independent verifier is
 * still false, perform a bounded read-only scroll so the next observe/verify
 * can see more content. After the cap, defer to the delegate.
 */
final class ScrollFallbackPolicy implements LoopPolicy {

    private final LoopPolicy delegate;
    private final int maxFallbacks;
    private final AtomicInteger fallbacks = new AtomicInteger();

    ScrollFallbackPolicy(LoopPolicy delegate, int maxFallbacks) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maxFallbacks < 0 || maxFallbacks > 3) {
            throw new IllegalArgumentException("maxFallbacks must be 0..3");
        }
        this.maxFallbacks = maxFallbacks;
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        Optional<LoopAction> action = delegate.next(context);
        if (action.isPresent()) {
            return action;
        }
        int index = fallbacks.getAndIncrement();
        if (index >= maxFallbacks) {
            fallbacks.decrementAndGet();
            return Optional.empty();
        }
        ScrollAction.Direction direction = index % 2 == 0
                ? ScrollAction.Direction.DOWN : ScrollAction.Direction.UP;
        return Optional.of(new ScrollAction("fallback.scroll." + (index + 1),
                "read more: scroll " + direction.name().toLowerCase(), direction, RiskClass.LOW));
    }

    @Override
    public boolean complete(LoopContext context) {
        return delegate.complete(context);
    }
}
