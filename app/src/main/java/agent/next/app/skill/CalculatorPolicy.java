package agent.next.app.skill;

import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.action.ActionExecutor;
import agent.next.brain.action.ActionProposal;
import agent.next.brain.action.SequenceAction;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.NodeSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** One governed visual/accessibility calculator sequence per subgoal. */
public final class CalculatorPolicy implements LoopPolicy {

    private final CalculatorExpression.Calculation calculation;
    private final ActionExecutor executor;
    private final TaskSpec task;
    private boolean attempted;

    public CalculatorPolicy(ActionExecutor executor, TaskSpec task, Subgoal subgoal) {
        this.executor = executor;
        this.task = task;
        this.calculation = CalculatorExpression.parse(task.goal(), subgoal.intent())
                .orElse(null);
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (calculation == null || attempted) {
            return Optional.empty();
        }
        attempted = true;
        List<ActionProposal> proposals = new ArrayList<>();
        int index = 0;
        for (String label : calculation.buttons()) {
            proposals.add(new ActionProposal.Tap("calculator." + (++index),
                    "tap calculator " + label, selectorFor(label), RiskClass.LOW));
        }
        return Optional.of(new SequenceAction(proposals, executor, task));
    }

    @Override
    public boolean complete(LoopContext context) {
        return false;
    }

    private static NodeSelector selectorFor(String label) {
        return switch (label) {
            case "C（清除）" -> NodeSelector.clickableContentDescriptionContains("清除");
            case "×" -> NodeSelector.clickableContentDescriptionContains("乘");
            case "=" -> NodeSelector.clickableContentDescriptionContains("等于");
            default -> NodeSelector.clickableTextContains(label);
        };
    }
}
