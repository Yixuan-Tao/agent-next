package agent.next.brain.contract;

import agent.next.brain.Subgoal;
import agent.next.brain.plan.TaskPlan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validates a plan against its TaskContract before execution or replanning.
 * Initial planning and replanning must use the same validator.
 */
public final class PlanValidator {

    public record Result(boolean valid, List<String> errors) {
        public Result {
            Objects.requireNonNull(errors, "errors");
            errors = List.copyOf(errors);
        }
    }

    public Result validate(TaskContract contract, TaskPlan plan,
            Set<String> registeredVerifiers) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(registeredVerifiers, "registeredVerifiers");
        List<String> errors = new ArrayList<>();
        if (!contract.taskId().equals(plan.task().id())) {
            errors.add("task id mismatch");
        }
        if (plan.subgoals().isEmpty() || plan.subgoals().size() > 8) {
            errors.add("subgoals must be 1..8");
        }
        String firstTarget = plan.subgoals().isEmpty()
                ? "" : plan.subgoals().get(0).targetPackage();
        if (!firstTarget.equals(contract.primaryPackage())) {
            errors.add("first subgoal must target primary package");
        }
        Set<String> planVerifiers = new HashSet<>();
        Set<String> evidenceText = new HashSet<>();
        for (Subgoal subgoal : plan.subgoals()) {
            if (!contract.allowedApps().contains(subgoal.targetPackage())) {
                errors.add("subgoal target not allowed: " + subgoal.targetPackage());
            }
            if (subgoal.verifierId().isBlank()) {
                errors.add("subgoal verifier is blank: " + subgoal.id());
            } else if (!contract.allowedVerifiers().contains(subgoal.verifierId())) {
                errors.add("verifier not allowed by contract: " + subgoal.verifierId());
            } else if (!registeredVerifiers.contains(subgoal.verifierId())) {
                errors.add("verifier not registered: " + subgoal.verifierId());
            }
            planVerifiers.add(subgoal.verifierId());
            evidenceText.add(subgoal.intent());
            subgoal.postconditions().forEach(condition -> evidenceText.add(condition.description()));
        }
        for (TaskContract.RequiredOutcome outcome : contract.requiredOutcomes()) {
            boolean covered;
            if (outcome.verifierId().isBlank()) {
                covered = evidenceText.stream()
                        .anyMatch(text -> text.contains(outcome.description()));
            } else {
                covered = planVerifiers.contains(outcome.verifierId());
            }
            if (!covered) {
                errors.add("required outcome not covered: " + outcome.id());
            }
        }
        return new Result(errors.isEmpty(), errors);
    }
}
