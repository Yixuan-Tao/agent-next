package agent.next.app.skill;

import agent.next.brain.loop.HumanHandoffRequiredException;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Observation;
import agent.next.device.UiNode;

import java.util.Locale;
import java.util.Optional;

/** Stops before verification/CAPTCHA/risk pages; optional human handoff. */
public final class RiskGuardPolicy implements LoopPolicy {

    @FunctionalInterface
    public interface Handoff {
        boolean handoff(String reason);
    }

    private static final String[] VERIFICATION_SIGNALS = {
            "京东验证", "安全验证", "人机验证", "验证码", "滑块验证", "captcha",
            "verify you are human", "unusual activity"
    };
    /** Stop at an actual order submission or payment boundary, not at the order review page. */
    private static final String[] PAYMENT_SIGNALS = {
            "提交订单", "立即支付", "确认支付", "收银台", "去支付", "支付密码"
    };

    private final LoopPolicy delegate;
    private final Handoff handoff;

    public RiskGuardPolicy(LoopPolicy delegate, Handoff handoff) {
        this.delegate = delegate;
        this.handoff = handoff;
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        Optional<String> blocked = handoffReason(context.observation());
        if (blocked.isPresent()) {
            String reason = blocked.orElseThrow();
            if (handoff != null && handoff.handoff(reason)) {
                return Optional.of(new WaitAction(1000));
            }
            throw new HumanHandoffRequiredException(reason);
        }
        return delegate.next(context);
    }

    /** Shared page boundary for both staged and reactive runtimes. */
    public static Optional<String> handoffReason(Observation observation) {
        for (UiNode node : observation.nodes()) {
            String text = (node.text() + " " + node.contentDescription())
                    .toLowerCase(Locale.ROOT);
            String reason = signal(text, VERIFICATION_SIGNALS, "human verification required: ");
            if (reason == null) {
                reason = signal(text, PAYMENT_SIGNALS, "payment requires a human: ");
            }
            if (reason == null) {
                continue;
            }
            return Optional.of(reason);
        }
        return Optional.empty();
    }

    private static String signal(String text, String[] signals, String prefix) {
        for (String signal : signals) {
            if (text.contains(signal.toLowerCase(Locale.ROOT))) {
                return prefix + signal;
            }
        }
        return null;
    }

    @Override
    public boolean complete(LoopContext context) {
        return delegate.complete(context);
    }
}
