package agent.next.app.files;

import agent.next.brain.ActionDescriptor;
import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;
import agent.next.brain.loop.LoopAction;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

import java.time.Instant;

/** Domain action: create a Download folder through MediaStore, not via the files UI. */
public final class FilesInsertAction implements LoopAction {

    private final FilesStore store;
    private final FilesStore.FolderSpec spec;

    public FilesInsertAction(FilesStore store, FilesStore.FolderSpec spec) {
        this.store = store;
        this.spec = spec;
    }

    @Override
    public String id() {
        return "files.folder";
    }

    @Override
    public String description() {
        return "create download folder: " + spec.name();
    }

    @Override
    public ActionKind kind() {
        return ActionKind.REVERSIBLE_WRITE;
    }

    @Override
    public RiskClass risk() {
        return RiskClass.REVERSIBLE_SIDE_EFFECT;
    }

    @Override
    public String payloadHash() {
        return Integer.toHexString(spec.name().hashCode());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        if (!store.insert(spec)) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "folder insert failed");
        }
        return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
    }

    @Override
    public ActionDescriptor descriptor(String targetApp) {
        return new ActionDescriptor(id(), description(), kind(), risk(), targetApp,
                payloadHash(), java.util.Set.of());
    }
}
