package agent.next.app.skill;

import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.RiskClass;
import agent.next.brain.loop.BackAction;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.Point;
import agent.next.device.UiNode;

import android.util.Log;

import java.util.Optional;

/** Deterministic JD search: type the query into the editable search box, then submit. */
public final class JdSearchPolicy implements LoopPolicy {

    private boolean dismissedAd;
    private int openAttempts;
    private boolean typed;
    private boolean submitted;

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (!dismissedAd) {
            String all = "";
            for (UiNode node : context.observation().nodes()) {
                all += node.text() + " " + node.contentDescription();
            }
            if (all.contains("挑好物逛京东") || all.contains("11.11主会场")) {
                dismissedAd = true;
                return Optional.of(new TapPointAction(new Point(495, 370)));
            }
        }
        UiNode editable = null;
        UiNode search = null;
        for (UiNode node : context.observation().nodes()) {
            if (editable == null && node.hasFlag(agent.next.device.NodeFlag.EDITABLE)
                    && !node.hasFlag(agent.next.device.NodeFlag.PASSWORD)) {
                editable = node;
            }
            if (search == null && node.hasFlag(agent.next.device.NodeFlag.CLICKABLE)
                    && (node.text().contains("搜索")
                    || node.contentDescription().contains("搜索"))) {
                search = node;
            }
        }
        Log.i("AgentNextRuntime", "JD search policy typed=" + typed + " submitted="
                + submitted + " editable=" + (editable != null)
                + " search=" + (search != null));
        if (!typed && editable != null) {
            typed = true;
            return Optional.of(new SetTextNodeAction(editable.ref(), query(context)));
        }
        if (!typed && editable == null && openAttempts < 3) {
            openAttempts++;
            int y = openAttempts == 1 ? 100 : (openAttempts == 2 ? 90 : 120);
            return Optional.of(new TapPointAction(new Point(360, y)));
        }
        if (!submitted && typed) {
            submitted = true;
            // The clickable "搜索" node is often the input field; the submit button is top-right.
            return Optional.of(new TapPointAction(new Point(644, 60)));
        }
        return Optional.of(new WaitAction(800));
    }

    private static String query(LoopContext context) {
        String goal = context.task().goal();
        int at = goal.indexOf("搜索");
        if (at >= 0) {
            String tail = goal.substring(at + 2);
            for (String stop : new String[] {"并", "然后", "，", ",", "。"}) {
                int end = tail.indexOf(stop);
                if (end > 0) {
                    tail = tail.substring(0, end);
                }
            }
            String value = tail.trim();
            if (!value.isEmpty() && value.length() <= 60) {
                return value;
            }
        }
        return "短袖T恤男款XL";
    }

    @Override
    public boolean complete(LoopContext context) {
        return submitted;
    }
}
