package agent.next.app.skill;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.DeviceException;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;

/** Verifies the calculator result from the observed display text. */
public final class CalculatorSubgoalVerifier implements SubgoalVerifier {

    @Override
    public Verification verify(TaskSpec task, Subgoal subgoal, TargetHandle target,
            Observation observation) throws DeviceException {
        if (!"com.miui.calculator".equals(target.packageName())) {
            return new Verification(false, "not calculator");
        }
        CalculatorExpression.Calculation calculation =
                CalculatorExpression.parse(task.goal(), subgoal.intent()).orElse(null);
        if (calculation == null) {
            return new Verification(false, "no calculation in task");
        }
        String expected = Long.toString(calculation.result());
        for (UiNode node : observation.nodes()) {
            String text = node.text().replace(",", "").replace(" ", "").trim();
            if (text.contains(expected)) {
                return new Verification(true, "calculator result visible: " + expected);
            }
        }
        return new Verification(false, "calculator result missing: " + expected);
    }
}
