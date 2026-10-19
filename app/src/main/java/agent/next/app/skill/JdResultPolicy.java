package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Point;
import agent.next.device.UiNode;

import java.util.Optional;

/**
 * JD search-result card selection. Taps a visible price/title node's bounds,
 * falling back to the first-card area; never taps buy/checkout.
 */
public final class JdResultPolicy implements LoopPolicy {

    private boolean tapped;

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (tapped) {
            return Optional.of(new WaitAction(800));
        }
        UiNode candidate = null;
        for (UiNode node : context.observation().nodes()) {
            String text = node.text();
            if (text.contains("¥") || text.contains("￥")) {
                if (node.bounds().top() > 200) {
                    candidate = node;
                    break;
                }
            }
        }
        tapped = true;
        if (candidate != null) {
            return Optional.of(new TapPointAction(new Point(
                    candidate.bounds().centerX(), candidate.bounds().centerY())));
        }
        return Optional.of(new TapPointAction(new Point(180, 360)));
    }

    @Override
    public boolean complete(LoopContext context) {
        return tapped;
    }
}
