package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.UiNode;

import java.util.Objects;
import java.util.Optional;

/**
 * Waits through empty/loading frames instead of spending model calls and
 * no-effect budget on a page that has not rendered its accessibility tree yet.
 * Native, WebView and React Native pages can stay blank for several seconds.
 */
public final class LoadingAwarePolicy implements LoopPolicy {

    private static final int LOADING_WAIT_MS = 2500;

    private final LoopPolicy delegate;

    public LoadingAwarePolicy(LoopPolicy delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (isBlank(context)) {
            return Optional.of(new WaitAction(LOADING_WAIT_MS));
        }
        return delegate.next(context);
    }

    @Override
    public boolean complete(LoopContext context) {
        return delegate.complete(context);
    }

    private static boolean isBlank(LoopContext context) {
        for (UiNode node : context.observation().nodes()) {
            if (!node.text().isBlank() || !node.contentDescription().isBlank()
                    || !node.viewId().isBlank()) {
                return false;
            }
        }
        // No nodes at all also means the window is still loading.
        return true;
    }
}
