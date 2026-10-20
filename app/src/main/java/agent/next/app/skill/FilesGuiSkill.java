package agent.next.app.skill;

import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.device.TargetHandle;
import agent.phone.capability.BlockingDeviceApi;

import java.util.Optional;

/** Pure-GUI Google Files adapter; only semantic selectors, no MediaStore writes. */
public final class FilesGuiSkill implements DomainSkill {

    private static final String PACKAGE = "com.google.android.apps.nbu.files";

    @Override
    public boolean matches(String packageName, TaskSpec task) {
        return PACKAGE.equals(packageName)
                && (task.goal().contains("文件夹") || task.goal().contains("目录"));
    }

    @Override
    public void prepareTarget(String packageName, TargetHandle target, TaskSpec task,
            BlockingDeviceApi api) {
        // Target launch is enough; all work is a semantic sequence.
    }

    @Override
    public Optional<LoopPolicy> policy(SkillContext context) {
        if (!PACKAGE.equals(context.target().packageName())
                || FolderName.parse(context.task().goal()).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new RiskGuardPolicy(
                new FilesGuiPolicy(context.executor(), context.task()), context.handoff()));
    }
}
