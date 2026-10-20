package agent.next.brain.loop;

import agent.next.brain.plan.PlanningResult;

/** Produces a candidate replacement plan for one slow-loop escalation. */
@FunctionalInterface
public interface ReplanSource {

    PlanningResult replan(SlowLoopContext context) throws Exception;
}
