package agent.next.brain.action;

import agent.next.brain.ActionDescriptor;
import agent.next.brain.ActionKind;
import agent.next.brain.ConfirmationRejectedException;
import agent.next.brain.HumanUnavailableException;
import agent.next.brain.RiskClass;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Composite GUI action: each proposal is grounded and executed independently
 * through the same ActionExecutor. A stop before any later proposal throws
 * CANCELLED, so the remaining steps are never dispatched.
 */
public final class SequenceAction implements LoopAction {

    private final List<ActionProposal> proposals;
    private final ActionExecutor executor;
    private final TaskSpec task;

    public SequenceAction(List<ActionProposal> proposals, ActionExecutor executor, TaskSpec task) {
        Objects.requireNonNull(proposals, "proposals");
        if (proposals.isEmpty()) {
            throw new IllegalArgumentException("proposals must not be empty");
        }
        this.proposals = List.copyOf(proposals);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.task = Objects.requireNonNull(task, "task");
    }

    @Override
    public String id() {
        return "sequence";
    }

    @Override
    public String description() {
        return "composite GUI sequence";
    }

    @Override
    public ActionKind kind() {
        return ActionKind.NAVIGATE;
    }

    @Override
    public RiskClass risk() {
        return RiskClass.LOW;
    }

    @Override
    public String payloadHash() {
        StringBuilder material = new StringBuilder();
        for (ActionProposal proposal : proposals) {
            material.append(proposal.id()).append('|').append(proposal.payloadHash()).append(';');
        }
        return Integer.toHexString(material.toString().hashCode());
    }

    @Override
    public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException {
        for (ActionProposal proposal : proposals) {
            try {
                executor.execute(task, target, proposal);
            } catch (LoopStoppedException stopped) {
                throw new DeviceException(DeviceException.Code.CANCELLED,
                        stopped.getMessage(), stopped);
            } catch (HumanUnavailableException unavailable) {
                throw new DeviceException(DeviceException.Code.NEED_USER,
                        unavailable.getMessage(), unavailable);
            } catch (ConfirmationRejectedException rejected) {
                throw new DeviceException(DeviceException.Code.NEED_USER,
                        rejected.getMessage(), rejected);
            }
        }
        return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
    }

    @Override
    public ActionDescriptor descriptor(String targetApp) {
        return new ActionDescriptor(id(), description(), kind(), risk(), targetApp,
                payloadHash(), java.util.Set.of());
    }
}
