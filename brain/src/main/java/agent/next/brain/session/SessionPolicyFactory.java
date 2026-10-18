package agent.next.brain.session;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.TargetHandle;

/** Builds a policy for one subgoal attempt; the app can inject PromptSkillPolicy. */
@FunctionalInterface
public interface SessionPolicyFactory {

    LoopPolicy create(TaskSpec task, Subgoal subgoal, TargetHandle target, int attempt);
}
