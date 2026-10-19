package agent.next.brain;

import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.FastLoop;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.LoopResult;
import agent.next.brain.loop.NodeSelector;
import agent.next.brain.loop.TapAction;
import agent.next.brain.loop.SlowLoopDecision;
import agent.next.brain.session.SessionPolicyFactory;
import agent.next.brain.session.SessionResult;
import agent.next.brain.session.SessionRunner;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.brain.session.TargetBinder;
import agent.next.brain.session.VerifierRegistry;
import agent.next.brain.TaskGraph;
import agent.next.brain.plan.TaskPlan;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Reproductions for the five defects recorded in the pure-GUI plan.
 *
 * <p>This class is intentionally not part of {@code BrainHostTest}: until M2/M3
 * these checks fail and would make the ordinary host gate red. Run it with
 * {@code tools/test-known-defects.sh}; each failure is a reproduced defect,
 * each pass means the defect was fixed and the check should move into the
 * normal suite.</p>
 */
public final class KnownDefectsTest {

    private KnownDefectsTest() {
    }

    public static void main(String[] args) {
        boolean reproduced = false;
        reproduced |= reproduce("eight subgoals hit six-attempt cap",
                KnownDefectsTest::eightSubgoals);
        reproduced |= reproduce("continuous progress stops at three",
                KnownDefectsTest::continuousProgress);
        System.out.println(reproduced
                ? "KnownDefectsTest: REPRODUCED (defects still open)"
                : "KnownDefectsTest: FIXED (promote checks into BrainHostTest)");
    }

    private static boolean reproduce(String name, Runnable check) {
        try {
            check.run();
            System.out.println("FIXED: " + name);
            return false;
        } catch (AssertionError failure) {
            System.out.println("REPRODUCED: " + name + " — " + failure.getMessage());
            return true;
        }
    }

    private static void lowRiskPayment() {
        DefaultRiskGate gate = new DefaultRiskGate();
        TaskSpec task = new TaskSpec("defect-payment", "pay now", List.of(),
                List.of(new SuccessCriterion("c1", "paid", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), Budget.defaults());
        ActionDescriptor payment = new ActionDescriptor("立即支付", "pay now",
                ActionKind.REVERSIBLE_WRITE, RiskClass.LOW, "com.example.shop",
                "payment-hash", Set.of());
        RiskDecision decision = gate.evaluate(payment, task);
        if (decision instanceof RiskDecision.Allow) {
            throw new AssertionError("low-risk payment reached Allow");
        }
    }

    private static void eightSubgoals() {
        List<Subgoal> subgoals = new ArrayList<>();
        for (int index = 1; index <= 8; index++) {
            subgoals.add(new Subgoal("s" + index, "step " + index, Set.of(),
                    List.of(), List.of(),
                    new Fallback(Fallback.Strategy.RETRY, "retry"),
                    RiskClass.LOW, true, "com.example.shop", "visual"));
        }
        TaskSpec task = new TaskSpec("defect-attempts", "eight steps", List.of(),
                List.of(new SuccessCriterion("c1", "done", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), Budget.defaults());
        VerifierRegistry verifiers = new VerifierRegistry()
                .register("visual", (taskSpec, subgoal, target, observation) ->
                        new SubgoalVerifier.Verification(true, "ok"));
        SessionPolicyFactory policies = (taskSpec, subgoal, target, attempt) ->
                (LoopPolicy) context -> Optional.empty();
        RecordingBinder binder = new RecordingBinder();
        SessionRunner runner = new SessionRunner(new LoopFakeDevice(),
                new TaskPlan(task, subgoals, List.of()), binder, policies,
                context -> new SlowLoopDecision.Stop("test"), verifiers,
                new DefaultRiskGate(), new NoHumanGateway(), LoopControl.none(),
                new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(),
                ObservationOptions.defaults(), 3, 6);
        SessionResult result = runner.run();
        if (result.status() != SessionResult.Status.DONE || result.attempts() < 8) {
            throw new AssertionError("status=" + result.status() + " attempts=" + result.attempts());
        }
    }

    private static void continuousProgress() {
        LoopFakeDevice api = new LoopFakeDevice(5);
        TaskSpec task = new TaskSpec("defect-progress", "five steps", List.of(),
                List.of(new SuccessCriterion("c1", "done", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(), new Budget(20, java.time.Duration.ofMinutes(1), 2, 3));
        Subgoal subgoal = new Subgoal("s1", "five steps", Set.of(),
                List.of(), List.of(), new Fallback(Fallback.Strategy.RETRY, "retry"),
                RiskClass.LOW, true, "com.example.shop", "visual");
        TaskGraph graph = TaskGraph.of(subgoal);
        LoopPolicy policy = new LoopPolicy() {
            
            public boolean complete(LoopContext context) {
                return context.observation().nodes().stream()
                        .anyMatch(node -> "step 5".equals(node.text()));
            }

            
            public Optional<LoopAction> next(LoopContext context) {
                if (complete(context)) {
                    return Optional.empty();
                }
                return Optional.of(new TapAction("next", "next step",
                        NodeSelector.textEquals("next"), RiskClass.LOW));
            }
        };
        LoopResult result = new FastLoop(api,
                new TargetHandle("defect-target", 1, "defect-progress", "com.example.shop"),
                task, graph, policy, new DefaultRiskGate(), new NoHumanGateway(),
                new InMemoryWorkingMemory(), new InMemoryEpisodicMemory()).run();
        if (result.status() != LoopResult.Status.DONE || result.actions() != 5) {
            throw new AssertionError("status=" + result.status() + " actions=" + result.actions());
        }
    }

    private static final class RecordingBinder implements TargetBinder {
        @Override
        public TargetHandle open(String packageName) {
            return new TargetHandle("defect-target", 1, "defect-task", packageName);
        }

        @Override
        public void release(TargetHandle target) {
        }
    }

    private static final class NoHumanGateway implements HumanGateway {
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
