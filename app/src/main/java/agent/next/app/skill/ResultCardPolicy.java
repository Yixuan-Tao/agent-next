package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Point;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Taps safe vision candidates in order until the observation shows a detail page. */
public final class ResultCardPolicy implements LoopPolicy {

    private final List<Point> candidates;
    private final Set<String> detailMarkers;
    private int next;

    public ResultCardPolicy(List<Point> candidates, Set<String> detailMarkers) {
        this.candidates = List.copyOf(candidates);
        this.detailMarkers = Set.copyOf(detailMarkers);
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        for (String marker : detailMarkers) {
            for (var node : context.observation().nodes()) {
                if (node.text().contains(marker)
                        || node.contentDescription().contains(marker)) {
                    return Optional.of(new WaitAction(800));
                }
            }
        }
        if (next < candidates.size()) {
            return Optional.of(new TapPointAction(candidates.get(next++)));
        }
        return Optional.of(new WaitAction(800));
    }
}
