package agent.next.brain.action;

import agent.next.brain.ActionKind;
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
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.NodeSelector;
import agent.next.device.TargetHandle;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static agent.next.brain.TestSupport.check;

/** Host tests for the M1 action proposal/grounding/executor boundary. */
public final class ActionExecutorTest {

    private ActionExecutorTest() {
    }

    public static void run() {
        tapAndSetText();
        paymentConfirmation();
        confirmationMismatch();
        noHumanDoesNotTap();
        stopBeforeAction();
        stopAfterConfirmation();
        System.out.println("ActionExecutorTest: PASS (grounding, risk, confirmation, cancel)");
    }

    private static void tapAndSetText() {
        LoopFakeDevice api = new LoopFakeDevice();
        ActionExecutor executor = executor(api, new Human(true));
        ActionExecutor.Execution tap = execute(executor, new ActionProposal.Tap(
                "tap.submit", "submit search", NodeSelector.textEquals("Search"),
                RiskClass.LOW));
        check(tap.receipt().accepted(), "tap accepted");
        check(api.taps() == 1, "tap executed once");

        LoopFakeDevice textApi = new LoopFakeDevice();
        ActionExecutor textExecutor = executor(textApi, new Human(true));
        ActionExecutor.Execution text = execute(textExecutor, new ActionProposal.SetText(
                "type.query", "type query", NodeSelector.editable(), "短袖T恤",
                RiskClass.REVERSIBLE_SIDE_EFFECT));
        check(text.receipt().verified(), "setText verified");
        check("短袖T恤".equals(text.receipt().actualText().orElse("")), "setText value");
    }

    private static void paymentConfirmation() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(true);
        ActionExecutor executor = executor(api, human);
        ActionExecutor.Execution execution = execute(executor, new ActionProposal.Tap(
                "tap.pay", "立即支付", NodeSelector.textEquals("立即支付"), RiskClass.LOW));
        check(execution.confirmed(), "payment required confirmation");
        check(human.request != null, "confirmation request delivered");
        check(human.request.payloadHash().equals(execution.action().descriptor().payloadHash()),
                "confirmation bound to payload hash");
        check(execution.action().descriptor().kind() == ActionKind.COMMITMENT,
                "payment raised to commitment");
        check(execution.action().descriptor().risk() == RiskClass.COMMITMENT,
                "payment raised to commitment risk");
        check(api.taps() == 1, "payment tapped after confirmation");
    }

    private static void confirmationMismatch() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(false);
        ActionExecutor executor = executor(api, human);
        try {
            execute(executor, new ActionProposal.Tap("tap.pay", "立即支付",
                    NodeSelector.textEquals("立即支付"), RiskClass.LOW));
            throw new AssertionError("mismatched confirmation must fail");
        } catch (agent.next.brain.ConfirmationRejectedException expected) {
            check(api.taps() == 0, "mismatched confirmation does not tap");
        }
    }

    private static void noHumanDoesNotTap() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(true);
        human.unavailable = true;
        ActionExecutor executor = executor(api, human);
        try {
            execute(executor, new ActionProposal.Tap("tap.pay", "立即支付",
                    NodeSelector.textEquals("立即支付"), RiskClass.LOW));
            throw new AssertionError("unavailable human must fail");
        } catch (RuntimeException expected) {
            check(expected.getCause() instanceof HumanUnavailableException,
                    "human unavailable propagates");
            check(api.taps() == 0, "unavailable human does not tap");
        }
    }

    private static void stopAfterConfirmation() {
        LoopFakeDevice api = new LoopFakeDevice(true);
        Human human = new Human(true);
        int[] checks = {0};
        LoopControl control = () -> {
            if (++checks[0] >= 2) {
                throw new agent.next.brain.loop.LoopStoppedException("stopped after confirm");
            }
        };
        ActionExecutor executor = new ActionExecutor(api, new DefaultRiskGate(), human, control);
        try {
            execute(executor, new ActionProposal.Tap("tap.pay", "立即支付",
                    NodeSelector.textEquals("立即支付"), RiskClass.LOW));
            throw new AssertionError("stop after confirmation must fail");
        } catch (RuntimeException expected) {
            check(human.request != null, "confirmation was requested");
            check(api.taps() == 0, "stop after confirmation performs no tap");
        }
    }

    private static void stopBeforeAction() {
        LoopFakeDevice api = new LoopFakeDevice();
        ActionExecutor executor = new ActionExecutor(api, new DefaultRiskGate(), new Human(true),
                () -> {
                    throw new agent.next.brain.loop.LoopStoppedException("stopped");
                });
        try {
            execute(executor, new ActionProposal.Tap("tap.submit", "submit",
                    NodeSelector.textEquals("Search"), RiskClass.LOW));
            throw new AssertionError("stop must fail before action");
        } catch (RuntimeException expected) {
            check(api.taps() == 0, "stop before action performs no tap");
        }
    }

    private static ActionExecutor.Execution execute(ActionExecutor executor,
            ActionProposal proposal) {
        try {
            return executor.execute(task(), target(), proposal);
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static ActionExecutor executor(LoopFakeDevice api, Human human) {
        return new ActionExecutor(api, new DefaultRiskGate(), human, LoopControl.none());
    }

    private static TaskSpec task() {
        return new TaskSpec("action-task", "test action", List.of(),
                List.of(new SuccessCriterion("c1", "action done", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), Budget.defaults());
    }

    private static TargetHandle target() {
        return new TargetHandle("target-1", 1, "action-task", "com.example.shop");
    }

    private static final class Human implements HumanGateway {
        private final boolean approve;
        private boolean unavailable;
        private ConfirmationRequest request;

        private Human(boolean approve) {
            this.approve = approve;
        }

        @Override
        public ConfirmationToken confirm(ConfirmationRequest request)
                throws HumanUnavailableException {
            if (unavailable) {
                throw new HumanUnavailableException("no human");
            }
            this.request = request;
            Instant now = Instant.now();
            String payload = approve ? request.payloadHash() : "wrong-payload";
            return new ConfirmationToken(request.actionId(), payload, now,
                    now.plusSeconds(60));
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
