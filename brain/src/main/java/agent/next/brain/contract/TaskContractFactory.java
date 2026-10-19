package agent.next.brain.contract;

import agent.next.brain.SuccessCriterion;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.TaskPlan;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds the post-plan contract used for PlanValidator and task-level checks. */
public final class TaskContractFactory {

    private TaskContractFactory() {
    }

    public static TaskContract from(PlanningRequest request, TaskPlan plan) {
        return from(request, plan, LocalDate.now(), ZoneId.systemDefault());
    }

    public static TaskContract from(PlanningRequest request, TaskPlan plan,
            LocalDate today, ZoneId zone) {
        List<TaskContract.RequiredOutcome> outcomes = new ArrayList<>();
        for (SuccessCriterion criterion : plan.task().successCriteria()) {
            if (criterion.required()) {
                outcomes.add(new TaskContract.RequiredOutcome(
                        criterion.id(), criterion.description(), ""));
            }
        }
        if (outcomes.isEmpty()) {
            outcomes.add(new TaskContract.RequiredOutcome("criterion-1", request.goal(), ""));
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        TaskTimeParser.parse(request.goal(), today, zone).ifPresent(time -> {
            parameters.put("time.start", time.start().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            parameters.put("time.end", time.end().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            parameters.put("time.zone", time.zone().getId());
        });
        return new TaskContract(request.taskId(), request.goal(), parameters, outcomes,
                request.constraints(), request.allowedApps(), request.primaryPackage(),
                request.deniedActions(), request.allowedVerifiers(), request.autonomy(),
                plan.task().budget(), 1);
    }
}
