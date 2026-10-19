package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Point;
import agent.next.device.UiNode;

import java.util.Optional;

/**
 * Deterministic JD detail add flow: tap 加入购物车, then the spec-sheet 确定.
 * Never taps 立即购买/结算.
 */
public final class JdAddPolicy implements LoopPolicy {

    private boolean confirmed;

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (confirmed) {
            return Optional.empty();
        }
        for (UiNode node : context.observation().nodes()) {
            String text = node.text() + " " + node.contentDescription();
            if (text.contains("加入购物车")) {
                return Optional.of(new TapPointAction(new Point(
                        node.bounds().centerX(), node.bounds().centerY())));
            }
        }
        boolean specSheet = false;
        for (UiNode node : context.observation().nodes()) {
            String text = node.text() + " " + node.contentDescription();
            if (text.contains("颜色") || text.contains("尺码")) {
                specSheet = true;
            }
        }
        if (specSheet) {
            for (UiNode node : context.observation().nodes()) {
                String text = node.text().trim();
                if ("确定".equals(text) || "确认".equals(text)) {
                    confirmed = true;
                    return Optional.of(new TapPointAction(new Point(
                            node.bounds().centerX(), node.bounds().centerY())));
                }
            }
        }
        return Optional.of(new WaitAction(800));
    }

    @Override
    public boolean complete(LoopContext context) {
        return confirmed;
    }
}
