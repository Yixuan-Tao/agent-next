package agent.next.brain.skill;

import agent.next.brain.loop.BackAction;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.SetTextAction;
import agent.next.brain.loop.TapAction;
import agent.next.device.Observation;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapts a workflow skill to the existing FastLoop policy port. Actions go
 * through FastLoop's RiskGate, so a skill cannot bypass confirmation or
 * handoff. The policy never receives a TargetHandle, DeviceApi, or Point.
 */
public final class SkillWorkflowPolicy implements LoopPolicy {

    private static final Pattern PARAMETER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private final SkillPackage skill;
    private final Map<String, String> parameters;
    private final List<SkillOperation> operations;
    private int index;

    public SkillWorkflowPolicy(SkillPackage skill, Map<String, String> parameters) {
        this.skill = Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(parameters, "parameters");
        this.parameters = Map.copyOf(parameters);
        this.operations = operations(skill.body());
        validateParameters();
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (!applicable(context.observation())) {
            return Optional.empty();
        }
        if (index >= operations.size()) {
            return Optional.empty();
        }
        SkillOperation operation = operations.get(index++);
        String stepId = skill.id() + ":" + index;
        try {
            if (operation instanceof SkillOperation.Tap tap) {
                return Optional.of(new TapAction(stepId, tap.description(), tap.selector(),
                        tap.risk()));
            }
            if (operation instanceof SkillOperation.SetText setText) {
                return Optional.of(new SetTextAction(stepId, setText.description(),
                        setText.selector(), render(setText.textTemplate()), setText.risk()));
            }
            SkillOperation.Back back = (SkillOperation.Back) operation;
            return Optional.of(new BackAction(stepId, back.description(), back.risk()));
        } catch (IllegalArgumentException invalidAction) {
            return Optional.empty();
        }
    }

    @Override
    public boolean complete(LoopContext context) {
        Observation observation = context.observation();
        if (!skill.permissions().allowsTarget(observation.packageName())) {
            return false;
        }
        try {
            SkillApplicability.Result applicable = skill.applicability().check(
                    observation, observation.packageName(), parameters);
            if (!applicable.applicable()) {
                return false;
            }
            return skill.verifier().verify(observation, parameters).satisfied();
        } catch (RuntimeException invalidVerification) {
            return false;
        }
    }

    private boolean applicable(Observation observation) {
        if (!skill.permissions().allowsTarget(observation.packageName())) {
            return false;
        }
        try {
            return skill.applicability().check(
                    observation, observation.packageName(), parameters).applicable();
        } catch (RuntimeException invalidCheck) {
            return false;
        }
    }

    private String render(String template) {
        Matcher matcher = PARAMETER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = parameters.get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException("missing parameter " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private void validateParameters() {
        for (SkillOperation operation : operations) {
            if (operation instanceof SkillOperation.SetText setText) {
                Matcher matcher = PARAMETER.matcher(setText.textTemplate());
                while (matcher.find()) {
                    if (!parameters.containsKey(matcher.group(1))) {
                        throw new IllegalArgumentException(
                                "missing skill parameter " + matcher.group(1));
                    }
                }
            }
        }
    }

    private static List<SkillOperation> operations(SkillBody body) {
        if (body instanceof SkillBody.Workflow workflow) {
            return workflow.operations();
        }
        if (body instanceof SkillBody.Hybrid hybrid) {
            return hybrid.operations();
        }
        throw new IllegalArgumentException("prompt skills require a model-backed policy");
    }
}
