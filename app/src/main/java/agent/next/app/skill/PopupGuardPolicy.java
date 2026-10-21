package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.skill.PopupDetector;
import agent.next.device.Point;

import android.util.Log;

import java.util.Objects;
import java.util.Optional;

/**
 * Declarative popup watcher in the policy chain: detects a benign dismissal
 * affordance before spending a model step, taps it, and falls back to the
 * rest of the chain (loading wait / model) when nothing matches.
 *
 * <p>Inspired by uiautomator2 Watcher/WatchContext, Android UiAutomator
 * UiWatcher, and the Appium UIWatchers plugin. Payment, credential and
 * verification surfaces are excluded by PopupDetector and belong to
 * RiskGuardPolicy's human handoff.</p>
 */
public final class PopupGuardPolicy implements LoopPolicy {

    private static final String TAG = "AgentNextPopup";
    /** Same popup may need a second tap if its animation swallows the first; never loop forever. */
    private static final int MAX_REPEAT = 2;

    private final LoopPolicy delegate;
    private String lastSignature;
    private int repeats;

    public PopupGuardPolicy(LoopPolicy delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        Optional<PopupDetector.Dismissal> found = PopupDetector.find(context.observation());
        if (found.isPresent()) {
            PopupDetector.Dismissal dismissal = found.get();
            if (dismissal.signature().equals(lastSignature)) {
                repeats++;
                if (repeats > MAX_REPEAT) {
                    // The same popup survived repeated taps: let the model/loading logic handle it.
                    return delegate.next(context);
                }
            } else {
                lastSignature = dismissal.signature();
                repeats = 0;
            }
            Log.i(TAG, "popup dismissed: " + dismissal.label()
                    + " node=" + dismissal.nodeRef());
            return Optional.of(new TapPointAction(new Point(dismissal.x(), dismissal.y())));
        }
        lastSignature = null;
        repeats = 0;
        return delegate.next(context);
    }

    @Override
    public boolean complete(LoopContext context) {
        return delegate.complete(context);
    }
}
