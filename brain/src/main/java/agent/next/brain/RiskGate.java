package agent.next.brain;

/** Policy port; implementations must be deterministic and rule-first. */
public interface RiskGate {

    RiskDecision evaluate(ActionDescriptor action, TaskSpec task);
}
