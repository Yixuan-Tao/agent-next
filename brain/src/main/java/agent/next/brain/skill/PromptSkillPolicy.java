package agent.next.brain.skill;

import agent.next.brain.RiskClass;
import agent.next.brain.loop.BackAction;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.SetTextAction;
import agent.next.brain.loop.TapAction;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.Observation;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Model-backed policy for prompt skills. The model sees only observation,
 * prompt, parameters, and capability names; it can only return semantic
 * selectors. It cannot emit coordinates or reach DeviceApi directly.
 *
 * <p>Only LOW / REVERSIBLE_SIDE_EFFECT model steps are accepted here. The
 * package trust and validation gates still apply, and every action goes
 * through FastLoop's RiskGate.</p>
 */
public final class PromptSkillPolicy implements LoopPolicy {

    private static final int DEFAULT_MAX_STEPS = 8;

    private final SkillPackage skill;
    private final String prompt;
    private final Map<String, String> parameters;
    private final SkillModel model;
    private final SkillFrameSource frameSource;
    private final int maxSteps;
    private int step;

    public PromptSkillPolicy(SkillPackage skill, Map<String, String> parameters, SkillModel model) {
        this(skill, parameters, model, () -> null, DEFAULT_MAX_STEPS);
    }

    public PromptSkillPolicy(SkillPackage skill, Map<String, String> parameters,
            SkillModel model, int maxSteps) {
        this(skill, parameters, model, () -> null, maxSteps);
    }

    public PromptSkillPolicy(SkillPackage skill, Map<String, String> parameters,
            SkillModel model, SkillFrameSource frameSource) {
        this(skill, parameters, model, frameSource, DEFAULT_MAX_STEPS);
    }

    public PromptSkillPolicy(SkillPackage skill, Map<String, String> parameters,
            SkillModel model, SkillFrameSource frameSource, int maxSteps) {
        this.skill = Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(parameters, "parameters");
        this.parameters = Map.copyOf(parameters);
        this.model = Objects.requireNonNull(model, "model");
        this.frameSource = Objects.requireNonNull(frameSource, "frameSource");
        if (!(skill.body() instanceof SkillBody.Prompt promptBody)) {
            throw new IllegalArgumentException("prompt policy requires a prompt skill body");
        }
        this.prompt = promptBody.prompt();
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (step >= maxSteps || !applicable(context.observation())) {
            return Optional.empty();
        }
        SkillModelRequest request = new SkillModelRequest(
                skill.id(), prompt, context.observation(),
                context.observation().packageName(), parameters,
                skill.permissions().capabilities(), step, captureFrame());
        SkillModelStep proposal;
        try {
            proposal = model.next(request);
        } catch (RuntimeException modelFailure) {
            return Optional.empty();
        }
        if (proposal == null) {
            return Optional.empty();
        }
        step++;
        if (!riskAllowed(proposal)) {
            return Optional.empty();
        }
        String stepId = skill.id() + ":model:" + step;
        if (proposal instanceof SkillModelStep.Tap tap) {
            return Optional.of(new TapAction(stepId, "model tap " + tap.selector().description(),
                    tap.selector(), tap.risk()));
        }
        if (proposal instanceof SkillModelStep.SetText setText) {
            return Optional.of(new SetTextAction(stepId,
                    "model set text on " + setText.selector().description(),
                    setText.selector(), setText.text(), setText.risk()));
        }
        if (proposal instanceof SkillModelStep.Back back) {
            return Optional.of(new BackAction(stepId, "model back", back.risk()));
        }
        return Optional.empty();
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

    private Frame captureFrame() {
        try {
            return frameSource.capture();
        } catch (DeviceException | RuntimeException unavailable) {
            return null;
        }
    }

    private boolean riskAllowed(SkillModelStep proposal) {
        RiskClass risk;
        if (proposal instanceof SkillModelStep.Tap tap) {
            risk = tap.risk();
        } else if (proposal instanceof SkillModelStep.SetText setText) {
            risk = setText.risk();
        } else if (proposal instanceof SkillModelStep.Back back) {
            risk = back.risk();
        } else {
            return true;
        }
        return risk == RiskClass.LOW || risk == RiskClass.REVERSIBLE_SIDE_EFFECT;
    }
}
