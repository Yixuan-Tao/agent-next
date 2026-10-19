package agent.next.app.skill;

import android.content.Context;

import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.TargetHandle;
import agent.next.app.files.FilesPolicy;
import agent.next.app.files.FilesStore;
import agent.next.app.files.FilesSubgoalVerifier;
import agent.phone.capability.BlockingDeviceApi;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Download-folder adapter through MediaStore; no file-manager UI automation. */
public final class FilesDomainSkill implements DomainSkill {

    private static final String PACKAGE = "com.android.fileexplorer";
    private final FilesStore store;
    private final FilesSubgoalVerifier verifier;

    public FilesDomainSkill(Context context) {
        this.store = new FilesStore(context);
        this.verifier = new FilesSubgoalVerifier(store);
    }

    @Override
    public boolean matches(String packageName, TaskSpec task) {
        return PACKAGE.equals(packageName);
    }

    @Override
    public void prepareTarget(String packageName, TargetHandle target, TaskSpec task,
            BlockingDeviceApi api) {
        // MediaStore writes need no file-manager UI preparation.
    }

    @Override
    public Optional<LoopPolicy> policy(SkillContext context) {
        if (!PACKAGE.equals(context.target().packageName())) {
            return Optional.empty();
        }
        return Optional.of(new RiskGuardPolicy(
                new FilesPolicy(store, context.task(), context.subgoal()), context.handoff()));
    }

    @Override
    public TaskPlan fixedPlan(TaskPlan planned) {
        if (planned == null || planned.subgoals().isEmpty()) {
            return null;
        }
        String goal = planned.task().goal();
        boolean folder = goal.contains("文件夹") || goal.contains("目录");
        String pkg = planned.subgoals().get(0).targetPackage();
        if (!folder || !PACKAGE.equals(pkg)) {
            return null;
        }
        Subgoal subgoal = new Subgoal("s1", "在下载目录创建文件夹", Set.of(),
                List.of(new Condition("s1.pre", "files domain ready", true)),
                List.of(new Condition("s1.post", "下载目录中存在目标文件夹", true)),
                new Fallback(Fallback.Strategy.RETRY, "retry once"),
                RiskClass.REVERSIBLE_SIDE_EFFECT, true, pkg, "files.folder");
        return new TaskPlan(planned.task(), List.of(subgoal),
                List.of("download folder path is deterministic"));
    }

    @Override
    public Set<String> verifierIds() {
        return Set.of("files.folder");
    }

    @Override
    public SubgoalVerifier verifier(SubgoalVerifier fallback) {
        return (task, subgoal, target, observation) -> {
            if (PACKAGE.equals(target.packageName())) {
                return verifier.verify(task, subgoal, target, observation);
            }
            return fallback.verify(task, subgoal, target, observation);
        };
    }
}
