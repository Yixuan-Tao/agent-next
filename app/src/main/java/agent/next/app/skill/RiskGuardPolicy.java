package agent.next.app.skill;

import agent.next.brain.loop.HumanHandoffRequiredException;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.UiNode;

import java.util.Locale;
import java.util.Optional;

/** Stops before verification/CAPTCHA/risk pages; no bypass attempts. */
public final class RiskGuardPolicy implements LoopPolicy {

    private static final String[] SIGNALS = {
            "京东验证", "安全验证", "人机验证", "验证码", "滑块验证", "captcha",
            "verify you are human", "unusual activity"
    };

    private final LoopPolicy delegate;

    public RiskGuardPolicy(LoopPolicy delegate) {
        this.delegate = delegate;
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        for (UiNode node : context.observation().nodes()) {
            String text = (node.text() + " " + node.contentDescription())
                    .toLowerCase(Locale.ROOT);
            for (String signal : SIGNALS) {
                if (text.contains(signal.toLowerCase(Locale.ROOT))) {
                    throw new HumanHandoffRequiredException(
                            "human verification required: " + signal);
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
