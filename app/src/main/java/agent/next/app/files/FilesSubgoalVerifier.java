package agent.next.app.files;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.DeviceException;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

/** Verifies the Download folder through MediaStore, not the files UI. */
public final class FilesSubgoalVerifier implements SubgoalVerifier {

    private final FilesStore store;

    public FilesSubgoalVerifier(FilesStore store) {
        this.store = store;
    }

    @Override
    public Verification verify(TaskSpec task, Subgoal subgoal, TargetHandle target,
            Observation observation) throws DeviceException {
        FilesStore.FolderSpec spec = FilesStore.specFor(task.goal(), subgoal.intent());
        boolean exists = store.exists(spec);
        return new Verification(exists, exists ? "download folder exists" : "download folder missing");
    }
}
