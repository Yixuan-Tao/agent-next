package agent.next.app.skill;

import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.SetTextAction;
import agent.next.brain.loop.TapAction;
import agent.next.device.NodeFlag;
import agent.next.brain.loop.NodeSelector;
import agent.next.device.Observation;
import agent.next.device.UiNode;

import java.util.Optional;

/** Narrow, user-confirmed weather lookup path for Quark; not a generic Quark driver. */
public final class QuarkWeatherPolicy implements LoopPolicy {

    private final String query;
    private int step;

    public QuarkWeatherPolicy(TaskSpec task, Subgoal subgoal) {
        String goal = task.goal() == null ? "" : task.goal();
        String city = goal.contains("上海") ? "上海" : "上海";
        this.query = city + "明天天气";
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        Observation observation = context.observation();
        if (hasWeatherResults(observation)) {
            return Optional.empty();
        }
        if (step == 0) {
            step = 1;
            if (findEditable(observation) == null) {
                return Optional.of(new WaitAction(900));
            }
            return Optional.of(new SetTextAction("quark.query", "type weather query",
                    NodeSelector.editable(), query, RiskClass.REVERSIBLE_SIDE_EFFECT));
        }
        if (step == 1) {
            step = 2;
            if (findClickableContaining(observation, "上海明天天气") != null) {
                return Optional.of(new TapAction("quark.suggestion", "open weather suggestion",
                        NodeSelector.textContains("上海明天天气预报"), RiskClass.LOW));
            }
            if (findClickableContaining(observation, "搜索") != null) {
                return Optional.of(new TapAction("quark.search", "submit weather search",
                        NodeSelector.clickableTextContains("搜索"), RiskClass.LOW));
            }
            return Optional.of(new WaitAction(900));
        }
        if (step == 2) {
            step = 3;
            return Optional.of(new WaitAction(1500));
        }
        return Optional.empty();
    }

    @Override
    public boolean complete(LoopContext context) {
        return false;
    }

    private static UiNode findEditable(Observation observation) {
        for (UiNode node : observation.nodes()) {
            if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                return node;
            }
        }
        return null;
    }

    private static UiNode findClickableContaining(Observation observation, String text) {
        for (UiNode node : observation.nodes()) {
            if (node.hasFlag(NodeFlag.CLICKABLE) && node.text().contains(text)) {
                return node;
            }
        }
        return null;
    }

    private static boolean hasWeatherResults(Observation observation) {
        boolean hasTabs = false;
        boolean hasWeather = false;
        for (UiNode node : observation.nodes()) {
            String text = node.text();
            if (text.equals("全部") || text.equals("AI搜索") || text.contains("AI搜索")) {
                hasTabs = true;
            }
            if (text.contains("天气") && (text.contains("℃") || text.contains("°")
                    || text.contains("降水") || text.contains("温度"))) {
                hasWeather = true;
            }
        }
        return hasTabs && hasWeather;
    }
}
