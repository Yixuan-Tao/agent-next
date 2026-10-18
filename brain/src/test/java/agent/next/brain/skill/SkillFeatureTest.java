package agent.next.brain.skill;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.ConfirmationRequest;
import agent.next.brain.ConfirmationToken;
import agent.next.brain.DefaultRiskGate;
import agent.next.brain.Fallback;
import agent.next.brain.HandoffRequest;
import agent.next.brain.HandoffSession;
import agent.next.brain.HumanGateway;
import agent.next.brain.HumanUnavailableException;
import agent.next.brain.InMemoryEpisodicMemory;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.FastLoop;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.LoopResult;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executable workflow-skill, registry, and admission tests. */
public final class SkillFeatureTest {

    private SkillFeatureTest() {
    }

    public static void run() {
        workflowRunsThroughFastLoop();
        permissionsAreEnforced();
        promptNeedsModel();
        applicabilityAndVerifier();
        registryAdmission();
        System.out.println("SkillFeatureTest: PASS (workflow, verifier, permissions, registry)");
    }

    private static void workflowRunsThroughFastLoop() {
        SkillPackage skill = skill("shop.search", new SkillBody.Workflow(List.of(
                new SkillOperation.SetText(SkillSelector.editable(), "${query}",
                        RiskClass.REVERSIBLE_SIDE_EFFECT),
                new SkillOperation.Tap(SkillSelector.clickableTextContains("Search"),
                        RiskClass.LOW))), SkillTrust.OFFICIAL, SkillApplicability.always());
        SkillWorkflowPolicy policy = new SkillWorkflowPolicy(skill, Map.of("query", "短袖T恤"));
        LoopFakeDevice api = new LoopFakeDevice();
        TaskGraph graph = graph();
        LoopResult result = new FastLoop(api, target(), task(), graph, policy,
                new DefaultRiskGate(), new NoHumanGateway(),
                new InMemoryWorkingMemory(), new InMemoryEpisodicMemory()).run();
        check(result.status() == LoopResult.Status.DONE, "skill workflow completes");
        check(result.actions() == 2, "skill workflow uses two actions");
        check(graph.isDone(), "skill workflow completes the graph");
    }

    private static void permissionsAreEnforced() {
        expect(IllegalArgumentException.class, () -> skill("bad.permission",
                new SkillBody.Workflow(List.of(
                        new SkillOperation.Tap(SkillSelector.textContains("Search"),
                                RiskClass.LOW))), SkillTrust.OFFICIAL, SkillApplicability.always(),
                new SkillPermissions(Set.of(SkillCapability.SET_TEXT), Set.of("com.example.shop"))),
                "undeclared tap capability must be rejected");
    }

    private static void promptNeedsModel() {
        SkillPackage prompt = skill("prompt.search", new SkillBody.Prompt("Find the query."),
                SkillTrust.VERIFIED, SkillApplicability.always());
        expect(IllegalArgumentException.class,
                () -> new SkillWorkflowPolicy(prompt, Map.of()),
                "prompt skill needs a model policy, not the workflow policy");
    }

    private static void applicabilityAndVerifier() {
        SkillPackage skill = skill("inapplicable.search", new SkillBody.Workflow(List.of(
                new SkillOperation.Tap(SkillSelector.textContains("Search"), RiskClass.LOW))),
                SkillTrust.OFFICIAL,
                (observation, targetPackage, parameters) ->
                        new SkillApplicability.Result(false, "wrong app state"));
        LoopResult result = new FastLoop(new LoopFakeDevice(), target(), task(), graph(),
                new SkillWorkflowPolicy(skill, Map.of()), new DefaultRiskGate(),
                new NoHumanGateway(), new InMemoryWorkingMemory(),
                new InMemoryEpisodicMemory()).run();
        check(result.status() == LoopResult.Status.SLOW_LOOP,
                "inapplicable skill produces no action");
        check(result.actions() == 0, "inapplicable skill does not act");

        SkillPackage badVerifier = skill("bad.verifier", new SkillBody.Workflow(List.of(
                new SkillOperation.Tap(SkillSelector.textContains("Search"), RiskClass.LOW))),
                SkillTrust.OFFICIAL, SkillApplicability.always(),
                new SkillPermissions(Set.of(SkillCapability.TAP), Set.of("com.example.shop")),
                (observation, parameters) ->
                        new SkillVerifier.Result(false, "no evidence"));
        boolean completed = new SkillWorkflowPolicy(badVerifier, Map.of())
                .complete(context(new LoopFakeDevice(), graph()));
        check(!completed, "verifier controls completion");
    }

    private static void registryAdmission() {
        SkillRegistry registry = new SkillRegistry();
        SkillPackage selfGenerated = skill("self.search", new SkillBody.Workflow(List.of(
                new SkillOperation.Tap(SkillSelector.textContains("Search"), RiskClass.LOW))),
                SkillTrust.SELF_GENERATED, SkillApplicability.always());
        expect(SkillRejectedException.class, () -> registry.install(selfGenerated, null),
                "self-generated skill requires validation");
        expect(SkillRejectedException.class, () -> registry.install(selfGenerated,
                new SkillValidation(false, "failed replay")),
                "failed validation is rejected");
        registry.install(selfGenerated, new SkillValidation(true, "replay passed"));
        check(registry.size() == 1, "validated skill is installed");

        SkillPackage official = skill("official.search", new SkillBody.Workflow(List.of(
                new SkillOperation.Tap(SkillSelector.textContains("Search"), RiskClass.LOW))),
                SkillTrust.OFFICIAL, SkillApplicability.always());
        registry.install(official, null);
        check(registry.find("official.search", "1.0.0").isPresent(), "official skill is installed");

        SkillCandidate candidate = new SkillCandidate(selfGenerated, "one successful trace");
        expect(SkillRejectedException.class,
                () -> registry.installCandidate(candidate,
                        ignored -> new SkillValidation(false, "still not enough evidence")),
                "candidate validation is required");
        SkillCandidate accepted = new SkillCandidate(
                skill("candidate.search", new SkillBody.Workflow(List.of(
                        new SkillOperation.Tap(SkillSelector.textContains("Search"),
                                RiskClass.LOW))), SkillTrust.SELF_GENERATED,
                        SkillApplicability.always()), "three traces");
        registry.installCandidate(accepted, ignored -> new SkillValidation(true, "matrix passed"));
        check(registry.find("candidate.search", "1.0.0").isPresent(), "accepted candidate is installed");
        registry.revoke("candidate.search", "1.0.0");
        check(registry.find("candidate.search", "1.0.0").isEmpty(), "revoked skill is gone");
    }

    private static SkillPackage skill(String id, SkillBody body, SkillTrust trust,
            SkillApplicability applicability) {
        return skill(id, body, trust, applicability,
                new SkillPermissions(Set.of(SkillCapability.TAP, SkillCapability.SET_TEXT),
                        Set.of("com.example.shop")));
    }

    private static SkillPackage skill(String id, SkillBody body, SkillTrust trust,
            SkillApplicability applicability, SkillPermissions permissions) {
        SkillVerifier verifier = (observation, parameters) -> {
            for (agent.next.device.UiNode node : observation.nodes()) {
                if (node.text().contains("Item added")) {
                    return new SkillVerifier.Result(true, "result item visible");
                }
            }
            return new SkillVerifier.Result(false, "result item not visible");
        };
        return skill(id, body, trust, applicability, permissions, verifier);
    }

    private static SkillPackage skill(String id, SkillBody body, SkillTrust trust,
            SkillApplicability applicability, SkillPermissions permissions,
            SkillVerifier verifier) {
        return new SkillPackage(id, "1.0.0", "search the shop", body, permissions,
                applicability, verifier, trust, new SkillProvenance(
                        "test", "agent-next-test", "sha256:test",
                        Instant.parse("2026-10-19T00:00:00Z")));
    }

    private static agent.next.brain.loop.LoopContext context(LoopFakeDevice api, TaskGraph graph) {
        try {
            Observation observation = api.observations().observe(target(),
                    agent.next.device.ObservationOptions.defaults());
            return new agent.next.brain.loop.LoopContext(task(), graph.current().orElseThrow(),
                    observation, 0, 0, 0, new InMemoryWorkingMemory());
        } catch (agent.next.device.DeviceException failure) {
            throw new AssertionError("observe failed", failure);
        }
    }

    private static TaskSpec task() {
        return new TaskSpec("skill-task", "search the shop", List.of(),
                List.of(new SuccessCriterion("done", "result visible", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.example.shop"), Set.of(),
                new Budget(10, Duration.ofMinutes(1), 2, 3));
    }

    private static TaskGraph graph() {
        return TaskGraph.of(new Subgoal("search", "search the shop", Set.of(), List.of(),
                List.of(), new Fallback(Fallback.Strategy.ASK_USER, "ask"),
                RiskClass.LOW, true));
    }

    private static TargetHandle target() {
        return new TargetHandle("skill-target", 1, "skill-task", "com.example.shop");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Throwable> type, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return;
            }
            throw new AssertionError(message + " (got " + failure + ")", failure);
        }
        throw new AssertionError(message);
    }

    private static final class NoHumanGateway implements HumanGateway {

        @Override
        public ConfirmationToken confirm(ConfirmationRequest request)
                throws HumanUnavailableException {
            throw new HumanUnavailableException("no human");
        }

        @Override
        public HandoffSession handoff(HandoffRequest request) throws HumanUnavailableException {
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
