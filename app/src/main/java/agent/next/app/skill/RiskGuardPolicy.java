package agent.next.app.skill;

import agent.next.brain.loop.HumanHandoffRequiredException;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.UiNode;

import java.util.Locale;
import java.util.Optional;

/** Stops before verification/CAPTCHA/risk pages; optional human handoff. */
public final class RiskGuardPolicy implements LoopPolicy {

    @FunctionalInterface
    public interface Handoff {
        boolean handoff(String reason);
    }

    private static final String[] SIGNALS = {
            "京东验证", "安全验证", "人机验证", "验证码", "滑块验证", "captcha",
            "verify you are human", "unusual activity"
    };

    private final LoopPolicy delegate;
    private final Handoff handoff;

    public RiskGuardPolicy(LoopPolicy delegate, Handoff handoff) {
        this.delegate = delegate;
        this.handoff = handoff;
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        for (UiNode node : context.observation().nodes()) {
            String text = (node.text() + " " + node.contentDescription())
                    .toLowerCase(Locale.ROOT);
            for (String signal : SIGNALS) {
                if (text.contains(signal.toLowerCase(Locale.ROOT))) {
                    String reason = "human verification required: " + signal;
                    if (handoff != null && handoff.handoff(reason)) {
                        return Optional.of(new WaitAction(1000));
                    }
                    throw new HumanHandoffRequiredException(reason);
                }
            }
        }
        return delegate.next(context);
    }

    @Override
    public boolean complete(LoopContext context) {
        return delegate.complete(context);
    }
}
