package agent.next.app.skill;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.TargetHandle;
import agent.phone.capability.BlockingDeviceApi;

/** Runtime services available to a domain skill for one subgoal attempt. */
public record SkillContext(
        TaskSpec task,
        Subgoal subgoal,
        TargetHandle target,
        int attempt,
        BlockingDeviceApi api,
        RiskGuardPolicy.Handoff handoff,
        LoopPolicy promptFallback) {
}
