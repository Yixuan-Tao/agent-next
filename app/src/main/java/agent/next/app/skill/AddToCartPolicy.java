package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Point;
import agent.next.device.UiNode;

import java.util.Optional;
import java.util.Set;

/** Add-to-cart by accessible labels first, then vision; never buy/checkout. */
public final class AddToCartPolicy implements LoopPolicy {

    private static final Set<String> ADD_FORBIDDEN =
            Set.of("立即购买", "结算", "去结算", "购买", "广告");
    private static final Set<String> CONFIRM_FORBIDDEN =
            Set.of("取消", "关闭", "立即购买", "结算", "去结算");
    private final AppProfile profile;
    private final JdVisualLocator locator;
    private boolean confirmed;
    private boolean visualAddTried;
    private boolean visualConfirmTried;

    public AddToCartPolicy(AppProfile profile, JdVisualLocator locator) {
        this.profile = profile;
        this.locator = locator;
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (confirmed) {
            return Optional.empty();
        }
        for (UiNode node : context.observation().nodes()) {
            String text = node.text() + " " + node.contentDescription();
            for (String label : profile.addLabels()) {
                if (text.contains(label)) {
                    return Optional.of(new TapPointAction(new Point(
                            node.bounds().centerX(), node.bounds().centerY())));
                }
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
                for (String label : profile.confirmLabels()) {
                    if (label.equals(text)) {
                        confirmed = true;
                        return Optional.of(new TapPointAction(new Point(
                                node.bounds().centerX(), node.bounds().centerY())));
                    }
                }
            }
            if (!visualConfirmTried) {
                visualConfirmTried = true;
                return Optional.of(new VisualTapAction(locator,
                        "京东规格选择弹层中的确定按钮，不要取消/关闭",
                        "jd.add.confirm.visual",
                        profile.confirmLabels(), CONFIRM_FORBIDDEN));
            }
        }
        if (!visualAddTried) {
            visualAddTried = true;
            return Optional.of(new VisualTapAction(locator,
                    "京东商品详情页底部的加入购物车按钮，不要立即购买/结算",
                    "jd.add.visual",
                    profile.addLabels(), ADD_FORBIDDEN));
        }
        return Optional.of(new WaitAction(1000));
    }

    @Override
    public boolean complete(LoopContext context) {
        return confirmed;
    }
}
