package agent.next.brain.plan;

import java.io.IOException;

/** Natural-language to validated TaskPlan; never emits device actions or coordinates. */
public interface TaskPlanner {

    PlanningResult plan(PlanningRequest request) throws IOException;
}
