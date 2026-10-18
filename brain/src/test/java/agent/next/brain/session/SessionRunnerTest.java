package agent.next.brain.session;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.Budget;
import agent.next.brain.DefaultRiskGate;
import agent.next.brain.Fallback;
import agent.next.brain.InMemoryEpisodicMemory;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.RiskClass;
import agent.next.brain.Subgoal;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskGraph;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.LoopResult;
import agent.next.brain.model.StageData;
import agent.next.brain.loop.SlowLoopDecision;
import agent.next.brain.plan.TaskPlan;
import agent.next.device.DeviceException;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** SessionRunner target switching and verifier-completion tests. */
public final class SessionRunnerTest {

    private SessionRunnerTest() {
    }

    public static void run() {
        switchesTargetsAndVerifies();
        failsClosedWithoutVerifier();
        System.out.println("SessionRunnerTest: PASS (target switch, verifier, cleanup)");
    }

    private static void switchesTargetsAndVerifies() {
        LoopFakeDevice api = new LoopFakeDevice();
        RecordingBinder binder = new RecordingBinder();
        TaskSpec task = task();
        List<Subgoal> subgoals = List.of(
                subgoal("weather", "查天气", "com.quark.browser"),
                subgoal("calendar", "建提醒", "com.android.calendar", "weather"));
        TaskPlan plan = new TaskPlan(task, subgoals, List.of());
        VerifierRegistry verifiers = new VerifierRegistry()
                .register("visual", (taskSpec, subgoal, target, observation) ->
                        new SubgoalVerifier.Verification(true, "verified " + subgoal.id()));
        SessionPolicyFactory policies = (taskSpec, subgoal, target, attempt) ->
                (LoopPolicy) context -> Optional.empty();
        InMemoryWorkingMemory memory = new InMemoryWorkingMemory();
        SessionRunner runner = new SessionRunner(api, plan, binder, policies,
                context -> new SlowLoopDecision.Stop("test"), verifiers,
                new DefaultRiskGate(), new NoHumanGateway(), LoopControl.none(),
                memory, new InMemoryEpisodicMemory(),
                ObservationOptions.defaults(), 3, 8,
                (taskSpec, subgoal, target, observation) ->
                        new StageData("summary " + subgoal.id(),
                                Map.of("entity", subgoal.id())));
        SessionResult result = runner.run();
        check(result.status() == SessionResult.Status.DONE, "session done");
        check(result.attempts() == 2, "two subgoal attempts");
        check(binder.opened.equals(List.of("com.quark.browser", "com.android.calendar")),
                "target packages switched in order");
        check(binder.released == 2, "both targets released");
        check(memory.get("stage.weather.summary").orElse("").contains("weather"),
                "stage summary persisted");
        check(memory.get("stage.last.entity.entity").orElse("").contains("calendar"),
                "last stage entity persisted");
    }

    private static void failsClosedWithoutVerifier() {
        LoopFakeDevice api = new LoopFakeDevice();
        RecordingBinder binder = new RecordingBinder();
        TaskPlan plan = new TaskPlan(task(), List.of(
                subgoal("weather", "查天气", "com.quark.browser")), List.of());
        SessionRunner runner = new SessionRunner(api, plan, binder,
                (taskSpec, subgoal, target, attempt) ->
                        (LoopPolicy) context -> Optional.empty(),
                context -> new SlowLoopDecision.Stop("test"), new VerifierRegistry(),
                new DefaultRiskGate(), new NoHumanGateway(), LoopControl.none(),
                new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(),
                ObservationOptions.defaults(), 3, 8);
        SessionResult result = runner.run();
        check(result.status() == SessionResult.Status.FAILED, "unknown verifier fails");
    }

    private static TaskSpec task() {
        return new TaskSpec("session-task", "weather then calendar", List.of(),
                List.of(new SuccessCriterion("done", "task done", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of("com.quark.browser", "com.android.calendar"),
                Set.of(), new Budget(10, Duration.ofMinutes(1), 2, 3));
    }

    private static Subgoal subgoal(String id, String intent, String target,
            String... dependencies) {
        return new Subgoal(id, intent, Set.of(dependencies), List.of(), List.of(),
                new Fallback(Fallback.Strategy.ASK_USER, "ask"), RiskClass.LOW, true,
                target, "visual");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class RecordingBinder implements TargetBinder {
        private final List<String> opened = new ArrayList<>();
        private int released;
        private int sequence;

        @Override
        public TargetHandle open(String packageName) {
            opened.add(packageName);
            sequence++;
            return new TargetHandle("session-target-" + sequence, 1, "session-task",
                    packageName);
        }

        @Override
        public void release(TargetHandle target) {
            released++;
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
