package agent.next.app.skill;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;

/** Deterministic JD cart check: title/list/footer evidence rather than another model call. */
public final class JdCartVerifier implements SubgoalVerifier {

    @Override
    public Verification verify(TaskSpec task, Subgoal subgoal, TargetHandle target,
            Observation observation) {
        if (!"com.jingdong.app.mall".equals(observation.packageName())) {
            return new Verification(false, "not JD: " + observation.packageName());
        }
        boolean title = false;
        boolean items = false;
        boolean footer = false;
        for (UiNode node : observation.nodes()) {
            String text = (node.text() + " " + node.contentDescription()).trim();
            if (text.contains("购物车")) {
                title = true;
            }
            if (text.contains("全选") || text.contains("件商品")
                    || text.contains("商品名称")) {
                items = true;
            }
            if (text.contains("合计") || text.contains("去结算")
                    || text.contains("共减")) {
                footer = true;
            }
        }
        boolean satisfied = title && (items || footer);
        return new Verification(satisfied,
                "cart title=" + title + " items=" + items + " footer=" + footer
                        + " nodes=" + observation.nodes().size());
    }
}
