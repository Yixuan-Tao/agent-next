package agent.next.brain.loop;

import agent.next.brain.ActionFlag;
import agent.next.brain.ActionKind;
import agent.next.brain.ActionDescriptor;
import agent.next.brain.RiskClass;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

import java.util.Set;

/**
 * One executable action chosen by the loop policy. Actions are semantic and
 * resolve their UI targets against the current observation; they do not carry
 * coordinates from a previous screen.
 */
public interface LoopAction {

    String id();

    String description();

    ActionKind kind();

    RiskClass risk();

    default Set<ActionFlag> flags() {
        return Set.of();
    }

    String payloadHash();

    InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
            throws DeviceException;

    default ActionDescriptor descriptor(String targetApp) {
        return new ActionDescriptor(id(), description(), kind(), risk(), targetApp,
                payloadHash(), flags());
    }
}
