package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Point;
import agent.next.device.UiNode;

import java.util.Optional;

/**
 * JD search-result card selection. Tries a price node, then two scoped first-card
 * points; never taps buy/checkout.
 */
public final class JdResultPolicy implements LoopPolicy {

    private int taps;

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (taps == 0) {
            for (UiNode node : context.observation().nodes()) {
                String text = node.text();
                if ((text.contains("¥") || text.contains("￥"))
                        && node.bounds().top() > 200) {
                    taps++;
                    return Optional.of(new TapPointAction(new Point(
                            node.bounds().centerX(), node.bounds().centerY())));
                }
            }
        }
        if (taps == 0 || taps == 1) {
            taps++;
            int y = taps == 1 ? 220 : 300;
            return Optional.of(new TapPointAction(new Point(180, y)));
        }
        return Optional.of(new WaitAction(800));
    }

    @Override
    public boolean complete(LoopContext context) {
        return taps >= 2;
    }
}
