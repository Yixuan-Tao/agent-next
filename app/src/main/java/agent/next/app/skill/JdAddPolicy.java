package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Point;
import agent.next.device.UiNode;

import java.util.Optional;

/** Deterministic add flow; labels come from AppProfile, never buy/checkout. */
public final class JdAddPolicy implements LoopPolicy {

    private final AppProfile profile;
    private boolean confirmed;

    public JdAddPolicy(AppProfile profile) {
        this.profile = profile;
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
        }
        return Optional.of(new WaitAction(800));
    }

    @Override
    public boolean complete(LoopContext context) {
        return confirmed;
    }
}
