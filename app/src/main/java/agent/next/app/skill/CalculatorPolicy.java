package agent.next.app.skill;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;

import java.util.Optional;

/** One visual calculator sequence per subgoal; verifier owns completion. */
public final class CalculatorPolicy implements LoopPolicy {

    private final CalculatorExpression.Calculation calculation;
    private final VisualButtonLocator locator;
    private boolean attempted;

    public CalculatorPolicy(VisualButtonLocator locator, TaskSpec task, Subgoal subgoal) {
        this.locator = locator;
        this.calculation = CalculatorExpression.parse(task.goal(), subgoal.intent())
                .orElse(null);
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (calculation == null || attempted) {
            return Optional.empty();
        }
        attempted = true;
        return Optional.of(new CalculatorSequenceAction(calculation, locator));
    }

    @Override
    public boolean complete(LoopContext context) {
        return false;
    }
}
