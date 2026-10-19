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
        try {
            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                    return;
                }
            }
            UiNode entry = null;
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.CLICKABLE)
                        && (node.text().contains("把问题和任务告诉我")
                        || node.text().contains("搜索"))) {
                    entry = node;
                    break;
                }
            }
            if (entry == null) {
                Log.i(TAG, "Quark entry not found");
                return;
            }
            api.input().tap(target, observation,
                    new Point(entry.bounds().centerX(), entry.bounds().centerY()));
            Thread.sleep(800L);
            api.observations().observe(target, ObservationOptions.defaults());
            Log.i(TAG, "Quark search entry opened");
        } catch (Throwable failure) {
            Log.w(TAG, "Quark entry prelude failed: " + failure);
        }
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
