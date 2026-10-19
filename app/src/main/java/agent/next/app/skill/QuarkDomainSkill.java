package agent.next.app.skill;

import android.util.Log;

import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.Point;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;
import agent.phone.capability.BlockingDeviceApi;

import java.util.Optional;

/** Narrow Quark weather skill; not a generic browser driver. */
public final class QuarkDomainSkill implements DomainSkill {

    private static final String PACKAGE = "com.quark.browser";
    private static final String TAG = "AgentNextRuntime";

    @Override
    public boolean matches(String packageName, TaskSpec task) {
        return PACKAGE.equals(packageName) && task.goal().contains("天气");
    }

    @Override
    public void prepareTarget(String packageName, TargetHandle target, TaskSpec task,
            BlockingDeviceApi api) {
        // Page preparation is a semantic action inside QuarkWeatherPolicy; no direct input here.
    }

    @Override
    public Optional<LoopPolicy> policy(SkillContext context) {
        if (!PACKAGE.equals(context.target().packageName())
                || !context.task().goal().contains("天气")) {
            return Optional.empty();
        }
        return Optional.of(new RiskGuardPolicy(
                new QuarkWeatherPolicy(context.task(), context.subgoal()),
                context.handoff()));
    }
}
