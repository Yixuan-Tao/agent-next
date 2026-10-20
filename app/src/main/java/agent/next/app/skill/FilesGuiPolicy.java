package agent.next.app.skill;

import agent.next.brain.RiskClass;
import agent.next.brain.TaskSpec;
import agent.next.brain.action.ActionExecutor;
import agent.next.brain.action.ActionProposal;
import agent.next.brain.action.SequenceAction;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.NodeSelector;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Deterministic Google Files "new folder" GUI workflow. */
public final class FilesGuiPolicy implements LoopPolicy {

    private final TaskSpec task;
    private final ActionExecutor executor;
    private final String folder;
    private boolean attempted;

    public FilesGuiPolicy(ActionExecutor executor, TaskSpec task) {
        this.task = task;
        this.executor = executor;
        this.folder = FolderName.parse(task.goal()).orElse(null);
    }

    @Override
    public Optional<LoopAction> next(LoopContext context) {
        if (folder == null || attempted) {
            return Optional.empty();
        }
        attempted = true;
        List<ActionProposal> steps = new ArrayList<>();
        steps.add(new ActionProposal.Tap("files.downloads", "open Downloads",
                NodeSelector.clickableTextContains("Downloads"), RiskClass.LOW));
        steps.add(new ActionProposal.Tap("files.more", "open overflow menu",
                NodeSelector.clickableContentDescriptionContains("More options"), RiskClass.LOW));
        steps.add(new ActionProposal.Tap("files.new", "choose New folder",
                NodeSelector.clickableTextContains("New folder"), RiskClass.LOW));
        steps.add(new ActionProposal.SetText("files.name", "enter folder name",
                NodeSelector.editable(), folder, RiskClass.LOW));
        steps.add(new ActionProposal.Tap("files.ok", "confirm folder",
                NodeSelector.clickableTextContains("OK"), RiskClass.LOW));
        steps.add(new ActionProposal.Tap("files.open", "open created folder",
                NodeSelector.clickableTextContains(folder), RiskClass.LOW));
        return Optional.of(new SequenceAction(steps, executor, task));
    }

    @Override
    public boolean complete(LoopContext context) {
        return false;
    }
}
