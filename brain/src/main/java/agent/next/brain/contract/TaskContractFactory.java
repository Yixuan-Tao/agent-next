package agent.next.brain.contract;

import agent.next.brain.SuccessCriterion;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.TaskPlan;

import java.util.ArrayList;
import java.util.List;

/** Builds the post-plan contract used for PlanValidator and task-level checks. */
public final class TaskContractFactory {

    private TaskContractFactory() {
    }

    public static TaskContract from(PlanningRequest request, TaskPlan plan) {
        List<TaskContract.RequiredOutcome> outcomes = new ArrayList<>();
        for (SuccessCriterion criterion : plan.task().successCriteria()) {
            if (criterion.required()) {
                outcomes.add(new TaskContract.RequiredOutcome(
                        criterion.id(), criterion.description(), ""));
            }
        }
        if (outcomes.isEmpty()) {
            outcomes.add(new TaskContract.RequiredOutcome(
                    "criterion-1", request.goal(), ""));
        }
        return new TaskContract(request.taskId(), request.goal(),
                java.util.Map.of(), outcomes, request.constraints(),
                request.allowedApps(), request.primaryPackage(), request.deniedActions(),
                request.allowedVerifiers(), request.autonomy(),
                plan.task().budget(), 1);
    }
}
