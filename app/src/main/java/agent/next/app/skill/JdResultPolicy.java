package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Point;

import java.util.Optional;

/** Taps a vision-located first product card exactly once; never buy/checkout. */
public final class JdResultPolicy implements LoopPolicy {

    private final Point point;
    private boolean tapped;

    public JdResultPolicy(Point point) {
        this.point = point;
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (!tapped) {
            tapped = true;
            return Optional.of(new TapPointAction(point));
        }
        return Optional.of(new WaitAction(800));
    }
}
