package agent.next.brain.contract;

import agent.next.brain.Subgoal;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.session.SessionResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Aggregates session status and plan coverage into a final TaskReport. */
public final class TaskReportBuilder {

    private TaskReportBuilder() {
    }

    public static TaskReport from(TaskContract contract, TaskPlan plan, SessionResult session) {
        Set<String> verifiers = new HashSet<>();
        Set<String> evidence = new HashSet<>();
        for (Subgoal subgoal : plan.subgoals()) {
            verifiers.add(subgoal.verifierId());
            evidence.add(subgoal.intent());
            subgoal.postconditions().forEach(c -> evidence.add(c.description()));
        }
        List<TaskReport.OutcomeResult> outcomes = new ArrayList<>();
        for (TaskContract.RequiredOutcome outcome : contract.requiredOutcomes()) {
            boolean covered = outcome.verifierId().isBlank()
                    ? evidence.stream().anyMatch(text -> text.contains(outcome.description()))
                    : verifiers.contains(outcome.verifierId());
            TaskReport.OutcomeState state;
            String why;
            if (session.status() != SessionResult.Status.DONE) {
                state = TaskReport.OutcomeState.UNKNOWN;
                why = "session did not finish: " + session.status();
            } else if (covered) {
                state = TaskReport.OutcomeState.SATISFIED;
                why = "plan evidence covered and all subgoals verified";
            } else {
                state = TaskReport.OutcomeState.UNSATISFIED;
                why = "required outcome not covered by plan evidence";
            }
            outcomes.add(new TaskReport.OutcomeResult(outcome.id(), outcome.description(),
                    state, why));
        }
        boolean all = outcomes.stream().allMatch(o -> o.state() == TaskReport.OutcomeState.SATISFIED);
        TaskReport.Status status;
        if (session.status() == SessionResult.Status.DONE) {
            status = all ? TaskReport.Status.DONE : TaskReport.Status.PARTIAL;
        } else {
            status = switch (session.status()) {
                case NEED_USER -> TaskReport.Status.NEED_USER;
                case PARTIAL -> TaskReport.Status.PARTIAL;
                case STOPPED -> TaskReport.Status.STOPPED;
                case DONE -> TaskReport.Status.DONE;
                default -> TaskReport.Status.FAILED;
            };
        }
        return new TaskReport(contract.taskId(), status, outcomes, session.actions(),
                session.attempts(), session.detail());
    }
}
