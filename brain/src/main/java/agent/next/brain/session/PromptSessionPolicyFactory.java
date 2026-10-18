package agent.next.brain.session;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;
import agent.next.brain.skill.PromptSkillPolicy;
import agent.next.brain.skill.SkillApplicability;
import agent.next.brain.skill.SkillBody;
import agent.next.brain.skill.SkillCapability;
import agent.next.brain.skill.SkillFrameSource;
import agent.next.brain.skill.SkillModel;
import agent.next.brain.skill.SkillPackage;
import agent.next.brain.skill.SkillPermissions;
import agent.next.brain.skill.SkillProvenance;
import agent.next.brain.skill.SkillTrust;
import agent.next.brain.skill.SkillVerifier;
import agent.next.device.TargetHandle;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Bridges a plan subgoal to a prompt skill; stage data comes from WorkingMemory. */
public final class PromptSessionPolicyFactory implements SessionPolicyFactory {

    private static final Set<SkillCapability> CAPABILITIES =
            Set.of(SkillCapability.TAP, SkillCapability.SET_TEXT);

    private final SkillModel model;
    private final TargetFrameSource frames;
    private final WorkingMemory memory;
    private final int maxSteps;

    public PromptSessionPolicyFactory(SkillModel model, TargetFrameSource frames,
            WorkingMemory memory) {
        this(model, frames, memory, 8);
    }

    public PromptSessionPolicyFactory(SkillModel model, TargetFrameSource frames,
            WorkingMemory memory, int maxSteps) {
        this.model = Objects.requireNonNull(model, "model");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.memory = Objects.requireNonNull(memory, "memory");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
    }

    @Override
    public agent.next.brain.loop.LoopPolicy create(TaskSpec task, Subgoal subgoal,
            TargetHandle target, int attempt) {
        String packageName = subgoal.targetPackage().isEmpty()
                ? target.packageName() : subgoal.targetPackage();
        if (!task.allowedApps().contains(packageName)) {
            throw new IllegalArgumentException("subgoal target is not allowed");
        }
        Map<String, String> parameters = parameters();
        SkillPackage skill = new SkillPackage(
                "session." + subgoal.id(),
                "1.0." + attempt,
                subgoal.intent(),
                new SkillBody.Prompt(prompt(task, subgoal, packageName)),
                new SkillPermissions(CAPABILITIES, Set.of(packageName)),
                (observation, targetPackage, values) ->
                        new SkillApplicability.Result(packageName.equals(targetPackage),
                                "subgoal target"),
                (observation, values) ->
                        new SkillVerifier.Result(false, "session verifier owns completion"),
                SkillTrust.VERIFIED,
                new SkillProvenance("plan-session", "agent-next", "generated",
                        Instant.now()));
        SkillFrameSource frameSource = () -> frames.capture(target);
        return new PromptSkillPolicy(skill, parameters, model, frameSource, maxSteps);
    }

    private Map<String, String> parameters() {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : memory.snapshot().entrySet()) {
            if (entry.getKey().startsWith("stage.")) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    private String prompt(TaskSpec task, Subgoal subgoal, String packageName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是手机 GUI 任务的语义操作员，不控制设备。只输出一个 JSON 语义动作。\n")
                .append("严禁输出坐标、x/y、像素或固定点击序列。\n")
                .append("任务目标：").append(task.goal()).append('\n')
                .append("当前阶段：").append(subgoal.id()).append(" | ")
                .append(subgoal.intent()).append('\n')
                .append("目标 App：").append(packageName).append('\n');
        if (!task.constraints().isEmpty()) {
            prompt.append("约束：").append(task.constraints()).append('\n');
        }
        if (!subgoal.postconditions().isEmpty()) {
            prompt.append("阶段完成条件（由独立 verifier 判断，不要自评完成）：\n");
            for (agent.next.brain.Condition condition : subgoal.postconditions()) {
                prompt.append("- ").append(condition.description()).append('\n');
            }
        }
        prompt.append("上一步结构化结果：\n");
        boolean stage = false;
        for (Map.Entry<String, String> entry : memory.snapshot().entrySet()) {
            if (entry.getKey().startsWith("stage.last.")) {
                prompt.append("- ").append(entry.getKey()).append('=')
                        .append(entry.getValue()).append('\n');
                stage = true;
            }
        }
        if (!stage) {
            prompt.append("- 无\n");
        }
        prompt.append("如果当前页面已经是目标页面，可以输出 Done；但完成仍由 verifier 判定。\n");
        return prompt.toString();
    }
}
