package agent.next.app.skill;

import agent.next.brain.RiskClass;
import agent.next.brain.loop.BackAction;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.NodeFlag;
import agent.next.device.Point;
import agent.next.device.UiNode;

import java.util.Optional;

/**
 * JD search using observed controls; an optional vision-located search entry is
 * allowed when the home page exposes no searchable node. No fixed coordinates.
 */
public final class JdSearchPolicy implements LoopPolicy {

    private final Point searchEntry;
    private final JdVisualLocator locator;
    private boolean dismissedAd;
    private boolean opened;
    private boolean typed;
    private boolean submitted;

    public JdSearchPolicy(Point searchEntry, JdVisualLocator locator) {
        this.searchEntry = searchEntry;
        this.locator = locator;
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        String all = "";
        UiNode editable = null;
        UiNode submit = null;
        for (UiNode node : context.observation().nodes()) {
            all += node.text() + " " + node.contentDescription();
            if (editable == null && node.hasFlag(NodeFlag.EDITABLE)
                    && !node.hasFlag(NodeFlag.PASSWORD)) {
                editable = node;
            }
            if (node.hasFlag(NodeFlag.CLICKABLE)
                    && (node.text().trim().equals("搜索")
                    || node.contentDescription().trim().equals("搜索"))
                    && node.bounds().top() < 220) {
                if (submit == null || node.bounds().right() > submit.bounds().right()) {
                    submit = node;
                }
            }
        }
        if (!dismissedAd && (all.contains("挑好物逛京东")
                || all.contains("11.11主会场"))) {
            dismissedAd = true;
            return Optional.of(new BackAction("jd.dismiss-ad",
                    "close JD home ad", RiskClass.LOW));
        }
        if (!typed && editable != null) {
            typed = true;
            return Optional.of(new SetTextNodeAction(editable.ref(), query(context)));
        }
        if (!typed && editable == null && !opened && searchEntry != null) {
            opened = true;
            return Optional.of(new TapPointAction(searchEntry));
        }
        if (!submitted && typed) {
            submitted = true;
            return Optional.of(new VisualTapAction(locator,
                    "京东搜索页右上角的搜索提交按钮，不要点输入框/拍照",
                    "jd.search.submit"));
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
