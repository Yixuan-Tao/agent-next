package agent.next.brain.action;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.ConfirmationRequest;
import agent.next.brain.ConfirmationToken;
import agent.next.brain.DefaultRiskGate;
import agent.next.brain.HandoffRequest;
import agent.next.brain.HandoffSession;
import agent.next.brain.HumanGateway;
import agent.next.brain.HumanUnavailableException;
import agent.next.brain.RiskClass;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.brain.loop.NodeSelector;
import agent.next.device.DeviceException;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.util.List;
import java.util.Set;

/** Composite stop must not dispatch the remaining proposals. */
public final class SequenceActionTest {

    private SequenceActionTest() {
    }

    public static void run() {
        LoopFakeDevice api = new LoopFakeDevice();
        ActionExecutor executor = new ActionExecutor(api, new DefaultRiskGate(),
                new NoHuman(), () -> {
                    if (api.taps() >= 1) {
                        throw new LoopStoppedException("stopped between steps");
                    }
                });
        SequenceAction sequence = new SequenceAction(List.of(
                new ActionProposal.Tap("step.1", "tap inert", NodeSelector.viewId("inert"),
                        RiskClass.LOW),
                new ActionProposal.Tap("step.2", "tap inert", NodeSelector.viewId("inert"),
                        RiskClass.LOW)), executor, task());
        try {
            sequence.execute(api, target(),
                    api.observations().observe(target(), ObservationOptions.defaults()));
            throw new AssertionError("sequence should have been cancelled");
        } catch (DeviceException failure) {
            if (failure.code() != DeviceException.Code.CANCELLED) {
                throw new AssertionError("expected CANCELLED, got " + failure.code());
            }
        }
        if (api.taps() != 1) {
            throw new AssertionError("remaining sequence step was dispatched: taps=" + api.taps());
        }
        System.out.println("SequenceActionTest: PASS (stop between steps)");
    }

    private static TaskSpec task() {
        return new TaskSpec("sequence-task", "two inert taps", List.of(),
                List.of(new SuccessCriterion("c1", "done", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), Budget.defaults());
    }

    private static TargetHandle target() {
        return new TargetHandle("sequence-target", 1, "sequence-task", "com.example.shop");
    }

    private static final class NoHuman implements HumanGateway {
        @Override
        public ConfirmationToken confirm(ConfirmationRequest request)
                throws HumanUnavailableException {
            throw new HumanUnavailableException("no human");
        }

        @Override
        public HandoffSession handoff(HandoffRequest request)
                throws HumanUnavailableException {
            throw new HumanUnavailableException("no human");
        }

        @Override
        public void endHandoff(HandoffSession session) {
        }

        @Override
        public void pause(String reason) {
        }

        @Override
        public void resume() {
        }

        @Override
        public void stop(String reason) {
        }
    }
}
