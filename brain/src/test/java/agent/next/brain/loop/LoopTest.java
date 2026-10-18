package agent.next.brain.loop;

import agent.next.brain.ActionFlag;
import agent.next.brain.ActionKind;
import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.ConfirmationRequest;
import agent.next.brain.ConfirmationToken;
import agent.next.brain.DefaultRiskGate;
import agent.next.brain.EpisodicMemory;
import agent.next.brain.Fallback;
import agent.next.brain.HandoffSession;
import agent.next.brain.HumanGateway;
import agent.next.brain.HumanUnavailableException;
import agent.next.brain.InMemoryEpisodicMemory;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.RiskClass;
import agent.next.brain.SlowLoopReason;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.WorkingMemory;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;
import agent.next.device.ObservationOptions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class LoopTest {

    private LoopTest() {
    }

    public static void run() {
        success();
        noEffectEscalates();
        budgetStops();
        credentialNeedsUser();
        confirmationIsRequired();
        stopControlStopsBeforeAction();
        rejectedActionRetries();
        System.out.println("LoopTest: PASS (fast loop, effect, slow-loop signal, risk)");
    }

    private static void stopControlStopsBeforeAction() {
        AtomicInteger checkpoints = new AtomicInteger();
        LoopControl control = () -> {
            if (checkpoints.incrementAndGet() >= 2) {
                throw new LoopStoppedException("user stopped");
            }
        };
        LoopResult result = loop(new LoopFakeDevice(), graph(), task(10), new SuccessPolicy(),
                new FakeHumanGateway(), new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(),
                control);
        check(result.status() == LoopResult.Status.STOPPED, "stop returns STOPPED");
        check(result.actions() == 0, "stop before action performs no action");
    }

    private static void success() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = graph();
        LoopResult result = loop(api, graph, task(10), new SuccessPolicy(),
                new FakeHumanGateway(), new InMemoryWorkingMemory(), new InMemoryEpisodicMemory());
        check(result.status() == LoopResult.Status.DONE, "success loop is done");
        check(result.actions() == 2, "success loop takes two actions");
        check(graph.isDone(), "success graph is done");
    }

    private static void noEffectEscalates() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = graph();
        LoopResult result = loop(api, graph, task(10), new NoEffectPolicy(),
                new FakeHumanGateway(), new InMemoryWorkingMemory(), new InMemoryEpisodicMemory());
        check(result.status() == LoopResult.Status.SLOW_LOOP, "no-effect escalates");
        check(result.reason().orElseThrow() == SlowLoopReason.NO_EFFECT, "no-effect reason");
        check(result.actions() == 2, "two no-effect actions");
    }

    private static void budgetStops() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = graph();
        LoopResult result = loop(api, graph, task(1), new SuccessPolicy(),
                new FakeHumanGateway(), new InMemoryWorkingMemory(), new InMemoryEpisodicMemory());
        check(result.status() == LoopResult.Status.BUDGET_EXHAUSTED, "action budget stops the loop");
        check(result.actions() == 1, "budget stops after one action");
    }

    private static void credentialNeedsUser() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = graph();
        CredentialAction action = new CredentialAction();
        LoopResult result = loop(api, graph, task(10), context -> Optional.of(action),
                new FakeHumanGateway(), new InMemoryWorkingMemory(), new InMemoryEpisodicMemory());
        check(result.status() == LoopResult.Status.NEED_USER, "credential hands off");
        check(result.actions() == 0, "credential action is not executed");
        check(!action.executed, "credential execute must not run");
    }

    private static void confirmationIsRequired() {
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = graph();
        CommitmentAction action = new CommitmentAction();
        FakeHumanGateway human = new FakeHumanGateway();
        LoopPolicy policy = context -> context.actions() == 0
                ? Optional.of(action) : Optional.<LoopAction>empty();
        LoopResult result = loop(api, graph, task(10), policy,
                human, new InMemoryWorkingMemory(), new InMemoryEpisodicMemory());
        check(human.confirmations == 1, "one confirmation");
        check(action.executed, "confirmed commitment executes");
        check(result.actions() == 1, "commitment counted as one action");
        check(result.status() == LoopResult.Status.SLOW_LOOP, "then slow loop because policy is exhausted");
        check(result.reason().orElseThrow() == SlowLoopReason.GRAPH_INCONSISTENT,
                "empty policy is a graph inconsistency");
    }

    private static void rejectedActionRetries() {
        java.util.concurrent.atomic.AtomicInteger step = new java.util.concurrent.atomic.AtomicInteger();
        LoopPolicy policy = new LoopPolicy() {
            @Override
            public boolean complete(LoopContext context) {
                for (UiNode node : context.observation().nodes()) {
                    if (node.text().contains("Item added")) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public Optional<LoopAction> next(LoopContext context) {
                int current = step.getAndIncrement();
                if (current == 0) {
                    return Optional.of(new SetTextAction("bad", "bad target",
                            NodeSelector.textEquals("missing"), "x",
                            RiskClass.REVERSIBLE_SIDE_EFFECT));
                }
                for (UiNode node : context.observation().nodes()) {
                    if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)
                            && node.text().isEmpty()) {
                        return Optional.of(new SetTextAction("type", "type query",
                                NodeSelector.editable(), "短袖T恤",
                                RiskClass.REVERSIBLE_SIDE_EFFECT));
                    }
                }
                for (UiNode node : context.observation().nodes()) {
                    if (node.hasFlag(NodeFlag.CLICKABLE) && "Search".equals(node.text())) {
                        return Optional.of(new TapAction("submit", "submit",
                                NodeSelector.textEquals("Search"), RiskClass.LOW));
                    }
                }
                return Optional.empty();
            }
        };
        LoopResult result = loop(new LoopFakeDevice(), graph(), task(10), policy,
                new FakeHumanGateway(), new InMemoryWorkingMemory(),
                new InMemoryEpisodicMemory());
        check(result.status() == LoopResult.Status.DONE, "retry after rejected action succeeds");
        check(result.actions() == 2, "rejected action not counted");
    }

    private static LoopResult loop(LoopFakeDevice api, TaskGraph graph, TaskSpec task,
            LoopPolicy policy, HumanGateway human, WorkingMemory working, EpisodicMemory episodic) {
        return new FastLoop(api, target(), task, graph, policy, new DefaultRiskGate(), human,
                working, episodic).run();
    }

    private static LoopResult loop(LoopFakeDevice api, TaskGraph graph, TaskSpec task,
            LoopPolicy policy, HumanGateway human, WorkingMemory working, EpisodicMemory episodic,
            LoopControl control) {
        return new FastLoop(api, target(), task, graph, policy, new DefaultRiskGate(), human,
                working, episodic, ObservationOptions.defaults(), 3, control).run();
    }

    static TaskSpec task(int maxActions) {
        return new TaskSpec(
                "loop-task",
                "find an item",
                List.of(),
                List.of(new SuccessCriterion("found", "item visible", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"),
                Set.of(),
                new Budget(maxActions, Duration.ofMinutes(1), 2, 3));
    }

    static TaskGraph graph() {
        return TaskGraph.of(new Subgoal("find", "find an item", Set.of(), List.of(), List.of(),
                new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, true));
    }

    static TargetHandle target() {
        return new TargetHandle("target-1", 1, "loop-task", "com.example.shop");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static final class SuccessPolicy implements LoopPolicy {

        @Override
        public boolean complete(LoopContext context) {
            for (UiNode node : context.observation().nodes()) {
                if (node.text().contains("Item added")) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Optional<LoopAction> next(LoopContext context) {
            for (UiNode node : context.observation().nodes()) {
                if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)
                        && node.text().isEmpty()) {
                    return Optional.of(new SetTextAction("type", "type the query",
                            NodeSelector.editable(), "短袖T恤", RiskClass.REVERSIBLE_SIDE_EFFECT));
                }
            }
            for (UiNode node : context.observation().nodes()) {
                if (node.hasFlag(NodeFlag.CLICKABLE) && "Search".equals(node.text())) {
                    return Optional.of(new TapAction("submit", "submit the search",
                            NodeSelector.textEquals("Search"), RiskClass.LOW));
                }
            }
            return Optional.empty();
        }
    }

    static final class NoEffectPolicy implements LoopPolicy {

        @Override
        public Optional<LoopAction> next(LoopContext context) {
            return Optional.of(new TapAction("noop", "tap an inert node",
                    NodeSelector.viewId("root"), RiskClass.LOW));
        }
    }

    private static final class CredentialAction implements LoopAction {

        private boolean executed;

        @Override
        public String id() {
            return "login";
        }

        @Override
        public String description() {
            return "enter a credential";
        }

        @Override
        public ActionKind kind() {
            return ActionKind.REVERSIBLE_WRITE;
        }

        @Override
        public RiskClass risk() {
            return RiskClass.CREDENTIAL;
        }

        @Override
        public Set<ActionFlag> flags() {
            return Set.of(ActionFlag.CREDENTIAL);
        }

        @Override
        public String payloadHash() {
            return "credential-payload";
        }

        @Override
        public InputReceipt execute(DeviceApi api, TargetHandle target,
                Observation observation) throws DeviceException {
            executed = true;
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }
    }

    private static final class CommitmentAction implements LoopAction {

        private boolean executed;

        @Override
        public String id() {
            return "commit";
        }

        @Override
        public String description() {
            return "submit the prepared action";
        }

        @Override
        public ActionKind kind() {
            return ActionKind.COMMITMENT;
        }

        @Override
        public RiskClass risk() {
            return RiskClass.COMMITMENT;
        }

        @Override
        public String payloadHash() {
            return "commit-payload";
        }

        @Override
        public InputReceipt execute(DeviceApi api, TargetHandle target,
                Observation observation) throws DeviceException {
            executed = true;
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }
    }

    static final class FakeHumanGateway implements HumanGateway {

        private int confirmations;

        @Override
        public ConfirmationToken confirm(ConfirmationRequest request) {
            confirmations++;
            return request.confirm(Instant.now(), Duration.ofMinutes(1));
        }

        @Override
        public HandoffSession handoff(agent.next.brain.HandoffRequest request)
                throws HumanUnavailableException {
            throw new HumanUnavailableException("handoff not scripted");
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
