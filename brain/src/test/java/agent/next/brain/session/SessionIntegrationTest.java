package agent.next.brain.session;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.DefaultRiskGate;
import agent.next.brain.InMemoryEpisodicMemory;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.SlowLoopDecision;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.ChatCompletionTransport;
import agent.next.brain.model.Json;
import agent.next.brain.model.ModelConfig;
import agent.next.brain.model.VisualVerification;
import agent.next.brain.model.VisualVerificationRequest;
import agent.next.brain.model.VisualVerifier;
import agent.next.brain.plan.DeepSeekTaskPlanner;
import agent.next.brain.plan.InstalledApp;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.PlanningResult;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.skill.SkillModel;
import agent.next.brain.skill.SkillModelStep;
import agent.next.brain.skill.SkillSelector;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** End-to-end host path: planner -> SessionRunner -> PromptSkill -> visual verifier. */
public final class SessionIntegrationTest {

    private SessionIntegrationTest() {
    }

    public static void run() {
        plannerToSession();
        System.out.println("SessionIntegrationTest: PASS (planner -> session -> prompt -> visual)");
    }

    private static void plannerToSession() {
        TaskPlan plan = plan();
        LoopFakeDevice api = new LoopFakeDevice();
        RecordingBinder binder = new RecordingBinder();
        InMemoryWorkingMemory memory = new InMemoryWorkingMemory();
        AtomicInteger visualCalls = new AtomicInteger();
        VisualVerifier visual = request -> new VisualVerification(
                visualCalls.incrementAndGet() >= 4, List.of(), List.of());
        TargetFrameSource frames = target -> new Frame(720, 1280,
                new byte[] {1, 2, 3}, Instant.now(), 1);
        VisualSubgoalVerifier verifier = new VisualSubgoalVerifier(visual, frames);
        VerifierRegistry verifiers = new VerifierRegistry().register("visual", verifier);
        SessionPolicyFactory policies = new PromptSessionPolicyFactory(
                skillModel(), frames, memory);
        SessionRunner runner = new SessionRunner(api, plan, binder, policies,
                context -> new SlowLoopDecision.Stop("test"), verifiers,
                new DefaultRiskGate(), new NoHumanGateway(), LoopControl.none(),
                memory, new InMemoryEpisodicMemory(), ObservationOptions.defaults(), 3, 8,
                (task, subgoal, target, observation) -> null);
        SessionResult result = runner.run();
        check(result.status() == SessionResult.Status.DONE, "session completed");
        check(result.actions() == 2, "two prompt actions");
        check(binder.opened.equals(List.of("com.example.shop")), "target opened");
        check(visualCalls.get() >= 4, "visual verifier consulted");
        check(memory.snapshot().isEmpty() || !memory.snapshot().containsKey("fake"),
                "memory not polluted");
    }

    private static TaskPlan plan() {
        String content = "{\"task\":{\"successCriteria\":[\"Item added\"]},"
                + "\"subgoals\":[{\"id\":\"shop\",\"intent\":\"search the shop\","
                + "\"dependsOn\":[],\"postconditions\":[\"evidence\"],\"targetPackage\":\"com.example.shop\","
                + "\"verifierId\":\"visual\",\"required\":true}],"
                + "\"assumptions\":[]}";
        ChatCompletionTransport transport = (url, auth, body) -> completion(content);
        DeepSeekTaskPlanner planner = new DeepSeekTaskPlanner(new ChatCompletionClient(
                new ModelConfig("https://example.test", "deepseek-flash", "test-key"),
                transport));
        try {
            PlanningResult result = planner.plan(new PlanningRequest(
                    "integration-task", "find a shirt", List.of(),
                    Set.of("com.example.shop"),
                    List.of(new InstalledApp("com.example.shop", "Shop")),
                    "com.example.shop", Set.of(), Set.of("visual"),
                    AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT)));
            check(!result.needsClarification(), "planner returned plan");
            return result.plan();
        } catch (IOException failure) {
            throw new AssertionError("planner I/O", failure);
        }
    }

    private static SkillModel skillModel() {
        return request -> switch (request.step()) {
            case 0 -> new SkillModelStep.SetText(SkillSelector.editable(), "短袖T恤",
                    RiskClass.REVERSIBLE_SIDE_EFFECT);
            case 1 -> new SkillModelStep.Tap(SkillSelector.clickableTextContains("Search"),
                    RiskClass.LOW);
            default -> new SkillModelStep.Done("done");
        };
    }

    private static String completion(String content) {
        return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                + Json.quote(content) + "}}]}";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingBinder implements TargetBinder {
        private final List<String> opened = new ArrayList<>();
        private int sequence;

        @Override
        public TargetHandle open(String packageName) {
            opened.add(packageName);
            sequence++;
            return new TargetHandle("integration-target-" + sequence, 1,
                    "integration-task", packageName);
        }

        @Override
        public void release(TargetHandle target) {
        }
    }

    private static final class NoHumanGateway implements agent.next.brain.HumanGateway {
        @Override
        public agent.next.brain.ConfirmationToken confirm(
                agent.next.brain.ConfirmationRequest request)
                throws agent.next.brain.HumanUnavailableException {
            throw new agent.next.brain.HumanUnavailableException("no human");
        }

        @Override
        public agent.next.brain.HandoffSession handoff(
                agent.next.brain.HandoffRequest request)
                throws agent.next.brain.HumanUnavailableException {
            throw new agent.next.brain.HumanUnavailableException("no human");
        }

        @Override
        public void endHandoff(agent.next.brain.HandoffSession session) {
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
