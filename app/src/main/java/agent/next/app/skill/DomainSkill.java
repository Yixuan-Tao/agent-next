package agent.next.app.skill;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.TargetHandle;
import agent.phone.capability.BlockingDeviceApi;

import java.util.Optional;

/** Optional app-specific adapter. Core runtime must not import concrete skills. */
public interface DomainSkill {

    boolean matches(String packageName, TaskSpec task);

    void prepareTarget(String packageName, TargetHandle target, TaskSpec task,
            BlockingDeviceApi api);

    Optional<LoopPolicy> policy(SkillContext context);

    default String verifierId() {
        return "";
    }

    default SubgoalVerifier verifier(SubgoalVerifier fallback) {
        return fallback;
    }
}
