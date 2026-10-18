package agent.next.brain.session;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.Condition;
import agent.next.brain.Fallback;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.ScrollAction;
import agent.next.brain.model.VisualVerification;
import agent.next.brain.model.VisualVerificationRequest;
import agent.next.brain.model.VisualVerifier;
import agent.next.brain.skill.SkillModel;
import agent.next.brain.skill.SkillModelRequest;
import agent.next.brain.skill.SkillModelStep;
import agent.next.device.Frame;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** App bridge tests: subgoal -> prompt skill and visual verifier. */
public final class SessionBridgeTest {

    private SessionBridgeTest() {
    }

    public static void run() {
        promptFactoryCarriesStageData();
        visualVerifierUsesConditions();
        try {
            scrollFallbackIsBounded();
        } catch (Exception failure) {
            throw new AssertionError("scroll fallback failed", failure);
        }
        System.out.println("SessionBridgeTest: PASS (prompt factory, visual verifier)");
    }

    private static void promptFactoryCarriesStageData() {
        LoopFakeDevice api = new LoopFakeDevice();
        TargetHandle target = new TargetHandle("bridge-target", 1, "bridge-task",
                "com.example.shop");
        Observation observation = observe(api, target);
        InMemoryWorkingMemory memory = new InMemoryWorkingMemory();
        memory.put("stage.last.summary", "天气晴，25 度");
        memory.put("stage.last.entity.city", "上海");
        AtomicReference<SkillModelRequest> captured = new AtomicReference<>();
        SkillModel model = request -> {
            captured.set(request);
            return new SkillModelStep.Done("done");
        };
        PromptSessionPolicyFactory factory = new PromptSessionPolicyFactory(
                model, ignored -> null, memory);
        LoopPolicy policy = factory.create(task(), subgoal(), target, 1);
        LoopContext context = new LoopContext(task(), subgoal(), observation, 0, 0, 0, memory);
        policy.next(context);
        check(captured.get() != null, "model invoked");
        check(captured.get().prompt().contains("查天气"), "prompt includes intent");
        check(captured.get().prompt().contains("天气晴"), "prompt includes stage summary");
        check(captured.get().parameters().get("stage.last.summary").contains("天气晴"),
                "parameters include stage summary");
    }

    private static void visualVerifierUsesConditions() {
        AtomicReference<VisualVerificationRequest> captured = new AtomicReference<>();
        VisualVerifier visual = request -> {
            captured.set(request);
            return new VisualVerification(true, List.of("结果页可见"), List.of());
        };
        TargetFrameSource frames = target -> new Frame(720, 1280,
                new byte[] {1, 2, 3}, Instant.now(), 1);
        VisualSubgoalVerifier verifier = new VisualSubgoalVerifier(visual, frames);
        try {
            SubgoalVerifier.Verification result = verifier.verify(task(), subgoal(),
                    new TargetHandle("bridge-target", 1, "bridge-task", "com.example.shop"),
                    observe(new LoopFakeDevice(),
                            new TargetHandle("bridge-target", 1, "bridge-task",
                                    "com.example.shop")));
            check(result.satisfied(), "visual verify passes");
            check(captured.get().conditions().contains("结果页可见"), "condition passed");
        } catch (Exception failure) {
            throw new AssertionError("visual verifier test failed", failure);
        }
    }

    private static void scrollFallbackIsBounded() throws Exception {
        LoopFakeDevice api = new LoopFakeDevice();
        TargetHandle target = new TargetHandle("scroll-target", 1, "scroll-task",
                "com.example.shop");
        Observation observation = observe(api, target);
        LoopContext context = new LoopContext(task(), subgoal(), observation, 0, 0, 0,
                new InMemoryWorkingMemory());
        ScrollFallbackPolicy policy = new ScrollFallbackPolicy(
                (LoopPolicy) ctx -> java.util.Optional.empty(), 2);
        LoopAction first = policy.next(context).orElseThrow();
        check(first instanceof ScrollAction, "first fallback is scroll");
        check(policy.next(context).orElseThrow() instanceof ScrollAction, "second scroll");
        check(policy.next(context).isEmpty(), "fallback capped");
        first.execute(api, target, observation);
    }

    private static Observation observe(LoopFakeDevice api, TargetHandle target) {
        try {
            return api.observations().observe(target, ObservationOptions.defaults());
        } catch (Exception failure) {
            throw new AssertionError("observation failed", failure);
        }
    }

    private static TaskSpec task() {
        return new TaskSpec("bridge-task", "查天气", List.of(),
                List.of(new SuccessCriterion("done", "结果页可见", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(),
                new Budget(10, Duration.ofMinutes(1), 2, 3));
    }

    private static Subgoal subgoal() {
        return new Subgoal("weather", "查天气", Set.of(), List.of(),
                List.of(new Condition("visible", "结果页可见", true)),
                new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, true,
                "com.example.shop", "visual");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
