package agent.next.brain.reactive;

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
import agent.next.brain.action.ActionProposal;
import agent.next.brain.action.ObservedNodeSelector;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.brain.loop.NodeSelector;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.DeepSeekReactiveModel;
import agent.next.brain.model.Json;
import agent.next.brain.model.ModelConfig;
import agent.next.brain.session.SessionResult;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.brain.session.TargetBinder;
import agent.next.device.Frame;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;
import agent.next.device.DeviceApi;
import agent.next.device.ObservationController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static agent.next.brain.TestSupport.check;
import static agent.next.brain.TestSupport.expect;

public final class ReactiveSessionTest {
    private ReactiveSessionTest() { }

    public static void run() {
        doneRequiresIndependentEvidence();
        failedSelectorsCannotSpin();
        acceptedTapWithoutEffectIsNotProgress();
        runsBeyondEightStepsFromCurrentPage();
        scopeAndBudgetAndStop();
        modelSelectsObservedNodeWithoutKeywords();
        independentEvidenceAcrossApps();
        pageRoundTripsAreBounded();
        distinctWaitsWithoutEffectAreBounded();
        BusinessLoopGuardTest.run();
        System.out.println("ReactiveSessionTest: PASS (page decisions, feedback, verification, scope, budget, cancel)");
    }

    private static void pageRoundTripsAreBounded() {
        LoopFakeDevice api = new LoopFakeDevice();
        List<String> events = new ArrayList<>();
        SessionResult result = run(api, task(20), request ->
                request.observation().activityName().equals("fake.Results")
                        ? new ReactiveDecision.Act(new ActionProposal.Back("back", "return", RiskClass.LOW))
                        : tap(request, 2), LoopControl.none(), events);
        check(result.status() == SessionResult.Status.FAILED && result.actions() < 10,
                "page round trip must end before spending the entire action budget");
        check(events.stream().anyMatch(s -> s.contains("往返")), "cycle feedback explains the repeated route");
    }

    private static void distinctWaitsWithoutEffectAreBounded() {
        List<String> events = new ArrayList<>();
        SessionResult result = run(new LoopFakeDevice(), task(20), request ->
                new ReactiveDecision.Act(new ActionProposal.Wait("wait-" + request.turn(), "loading", 100 + request.turn())),
                LoopControl.none(), events);
        check(result.status() == SessionResult.Status.FAILED && result.actions() == 5,
                "different waits cannot evade consecutive no-effect budget");
        check(events.stream().anyMatch(s -> s.contains("连续无效果=4")), "configured threshold permits one bounded recovery");
    }

    private static void independentEvidenceAcrossApps() {
        LoopFakeDevice underlying = new LoopFakeDevice();
        DeviceApi device = (DeviceApi) java.lang.reflect.Proxy.newProxyInstance(DeviceApi.class.getClassLoader(),
                new Class<?>[] {DeviceApi.class}, (proxy, method, args) -> {
                    if (method.getName().equals("observations")) {
                        return new ObservationController() {
                            @Override public Observation observe(TargetHandle target, ObservationOptions options) throws agent.next.device.DeviceException {
                                Observation original = underlying.observations().observe(target, options);
                                return new Observation(original.status(), original.reference().orElseThrow(),
                                        target.packageName(), original.activityName(), original.displayId(), original.windowId(),
                                        original.stable(), original.capturedAt(), original.lookupCount(), original.truncated(),
                                        original.windowBounds().orElseThrow(), original.nodes());
                            }
                            @Override public Frame captureFrame(TargetHandle target) { return frame(); }
                        };
                    }
                    try { return method.invoke(underlying, args); }
                    catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        TaskSpec base = task(20);
        TaskSpec task = new TaskSpec(base.id(), "verify two app results", List.of(),
                List.of(new SuccessCriterion("first", "first app result", true),
                        new SuccessCriterion("second", "second app result", true)), base.autonomy(),
                Set.of("com.example.shop", "com.example.other"), Set.of(), base.budget());
        AtomicInteger checks = new AtomicInteger();
        List<String> opened = new ArrayList<>();
        ReactiveModel model = request -> switch (request.turn()) {
            case 0 -> new ReactiveDecision.CheckResult(List.of("first"));
            case 1 -> new ReactiveDecision.OpenApp("com.example.other");
            case 2 -> new ReactiveDecision.CheckResult(List.of("second"));
            default -> new ReactiveDecision.Done("both results independently checked");
        };
        SessionResult result = new ReactiveSessionRunner(device, task, "com.example.shop", new TargetBinder() {
            @Override public TargetHandle open(String app) {
                opened.add(app);
                return new TargetHandle("target", 1, "task", app);
            }
            @Override public void release(TargetHandle target) { }
        }, model, target -> frame(), (spec, outcome, target, observed) -> {
            checks.incrementAndGet();
            check(outcome.postconditions().size() == 1, "verify current app's actual final result separately");
            return new SubgoalVerifier.Verification(true, "current app evidence verified");
        }, new DefaultRiskGate(), new NoHuman(), LoopControl.none(), reason -> false, event -> { }).run();
        check(result.status() == SessionResult.Status.DONE && checks.get() == 2,
                "cross-app completion requires independently verified evidence for each outcome");
        check(opened.equals(List.of("com.example.shop", "com.example.other")), "apps chosen from current decisions");
    }

    private static Frame frame() { return new Frame(720, 1280, new byte[] {1}, Instant.now(), 1); }

    private static void doneRequiresIndependentEvidence() {
        LoopFakeDevice api = new LoopFakeDevice();
        ReactiveModel model = request -> {
            if (request.turn() == 0) return new ReactiveDecision.Done("I think so");
            if (request.turn() == 1) {
                check(request.history().stream().anyMatch(s -> s.contains("核验否决")), "verifier failure returns to model");
                return tap(request, 2);
            }
            check(request.observation().nodes().stream().anyMatch(n -> n.text().equals("Item added")),
                    "next decision sees actual post-action page");
            return new ReactiveDecision.Done("result is visible");
        };
        SessionResult result = run(api, task(20), model, LoopControl.none(), new ArrayList<>());
        check(result.status() == SessionResult.Status.DONE && api.taps() == 1,
                "premature Done is denied and agent continues to actual completion");
    }

    private static void failedSelectorsCannotSpin() {
        LoopFakeDevice api = new LoopFakeDevice();
        List<String> events = new ArrayList<>();
        ReactiveModel model = request -> new ReactiveDecision.Act(new ActionProposal.Tap("bad", "missing product",
                NodeSelector.textContains("invented product"), RiskClass.LOW));
        SessionResult result = run(api, task(20), model, LoopControl.none(), events);
        check(result.status() == SessionResult.Status.FAILED && api.taps() == 0, "zero-match repeats end with an explicit failure, without requesting takeover");
        check(events.stream().anyMatch(s -> s.contains("STALE")), "actual matching failure is recorded");
        check(events.stream().anyMatch(s -> s.contains("阻止重复失败")), "repeated failed choice is blocked");
    }

    private static void acceptedTapWithoutEffectIsNotProgress() {
        LoopFakeDevice api = new LoopFakeDevice();
        SessionResult result = run(api, task(20), request -> tap(request, 3), LoopControl.none(), new ArrayList<>());
        check(result.status() == SessionResult.Status.FAILED && api.taps() == 1,
                "unchanged page prevents repeated tapping of inert element");
    }

    private static void runsBeyondEightStepsFromCurrentPage() {
        LoopFakeDevice api = new LoopFakeDevice(12);
        ReactiveModel model = request -> request.observation().nodes().stream().anyMatch(n -> n.text().equals("step 12"))
                ? new ReactiveDecision.Done("twelve pages traversed") : tap(request, 2);
        SessionResult result = run(api, task(20), model, LoopControl.none(), new ArrayList<>());
        check(result.status() == SessionResult.Status.DONE && api.taps() == 12,
                "task loop has no per-skill eight-step cap");
    }

    private static void scopeAndBudgetAndStop() {
        LoopFakeDevice api = new LoopFakeDevice(12);
        SessionResult budget = run(api, task(1), request -> tap(request, 2), LoopControl.none(), new ArrayList<>());
        check(budget.status() == SessionResult.Status.BUDGET_EXHAUSTED && api.taps() == 1, "task action cap remains enforced");
        SessionResult scope = run(new LoopFakeDevice(), task(20),
                request -> new ReactiveDecision.OpenApp("com.forbidden.app"), LoopControl.none(), new ArrayList<>());
        check(scope.status() == SessionResult.Status.FAILED, "out-of-scope app cannot be launched or request broader permissions");
        SessionResult stopped = run(new LoopFakeDevice(), task(20), request -> new ReactiveDecision.Done("done"),
                () -> { throw new LoopStoppedException("user stopped"); }, new ArrayList<>());
        check(stopped.status() == SessionResult.Status.STOPPED, "cancellation checked before opening target");
    }

    private static void modelSelectsObservedNodeWithoutKeywords() {
        LoopFakeDevice api = new LoopFakeDevice();
        try {
            Observation observation = api.observations().observe(target(), ObservationOptions.defaults());
            AtomicReference<String> body = new AtomicReference<>();
            ReactiveRequest request = new ReactiveRequest(task(20), observation, null, List.of("previous lookup failed"), 0);
            ReactiveDecision decision = model("{\"kind\":\"Tap\",\"effect\":null,\"nodeIndex\":2}", body).next(request);
            check(decision instanceof ReactiveDecision.Act act && act.proposal() instanceof ActionProposal.Tap tap
                    && tap.selector() instanceof ObservedNodeSelector, "current node selection needs no label selector");
            check(body.get().contains("index=2") && body.get().contains("previous lookup failed"), "model gets current indices and feedback");
            ReactiveDecision expected = model("{\"kind\":\"Tap\",\"effect\":null,\"nodeIndex\":2,\"expectedChange\":\"商品列表出现\"}", body).next(request);
            check(((ReactiveDecision.Act) expected).expectedChange().equals("商品列表出现"), "expected effect is retained as a hypothesis");
            ReactiveDecision progress = model("{\"kind\":\"Tap\",\"effect\":null,\"nodeIndex\":2,\"progress\":\"正在搜索商品\"}", body).next(request);
            check(((ReactiveDecision.Act) progress).progress().equals("正在搜索商品"), "short user progress is separate from internal node selection");
            ReactiveDecision write = model("{\"kind\":\"Tap\",\"nodeIndex\":2,\"progress\":\"正在加购\",\"effect\":{\"key\":\"add:shirt:black:xl:1\",\"postcondition\":\"购物车指定SKU总数1\",\"absenceCondition\":\"完整购物车指定SKU不存在\"}}", body).next(request);
            check(((ReactiveDecision.Act) write).effect().key().equals("add:shirt:black:xl:1"), "effect survives progress wrapper");
            expect(IllegalArgumentException.class, () -> model("{\"kind\":\"Tap\",\"nodeIndex\":2}", body).next(request), "Tap must explicitly declare navigation or mutation");
            expect(IllegalArgumentException.class, () -> model("{\"kind\":\"Tap\",\"nodeIndex\":2,\"effect\":{\"key\":\"add\"}}", body).next(request), "mutation needs verifiable conditions");
            expect(IllegalArgumentException.class, () -> model("{\"kind\":\"Tap\",\"effect\":null,\"nodeIndex\":999}", body).next(request), "invented node rejected");
            expect(IllegalArgumentException.class, () -> model("{\"kind\":\"Tap\",\"effect\":null,\"nodeIndex\":2,\"x\":10}", body).next(request), "raw coordinate rejected");
            expect(IllegalArgumentException.class, () -> model("{\"kind\":\"OpenApp\",\"packageName\":\"com.forbidden.app\"}", body).next(request), "model app scope checked");
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static DeepSeekReactiveModel model(String answer, AtomicReference<String> body) {
        return new DeepSeekReactiveModel(new ChatCompletionClient(new ModelConfig("https://example.test", "test", "test-key"),
                (url, auth, payload) -> {
                    body.set(payload);
                    return "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":" + Json.quote(answer) + "}}]}";
                }));
    }

    private static SessionResult run(DeviceApi api, TaskSpec task, ReactiveModel model,
            LoopControl control, List<String> events) {
        SubgoalVerifier verifier = (spec, outcome, target, observation) -> new SubgoalVerifier.Verification(
                observation.nodes().stream().anyMatch(n -> n.text().equals("Item added") || n.text().equals("step 12")),
                "visible result required");
        return run(api, task, model, control, events, verifier);
    }

    static SessionResult run(DeviceApi api, TaskSpec task, ReactiveModel model,
            LoopControl control, List<String> events, SubgoalVerifier verifier) {
        AtomicInteger frames = new AtomicInteger();
        return new ReactiveSessionRunner(api, task, "com.example.shop", new TargetBinder() {
            @Override public TargetHandle open(String app) {
                check(app.equals("com.example.shop"), "must not launch forbidden apps");
                return target();
            }
            @Override public void release(TargetHandle target) { }
        }, model, target -> new Frame(720, 1280, new byte[] {1}, Instant.now(), frames.incrementAndGet()),
                verifier, new DefaultRiskGate(), new NoHuman(), control, reason -> false, events::add).run();
    }

    private static ReactiveDecision tap(ReactiveRequest request, int index) {
        return new ReactiveDecision.Act(new ActionProposal.Tap("tap", "tap current control",
                new ObservedNodeSelector(request.observation(), index), RiskClass.LOW));
    }

    static TaskSpec task(int maxActions) {
        return new TaskSpec("task", "complete visible result", List.of(),
                List.of(new SuccessCriterion("result", "result is visible", true)),
                AutonomyGrant.of(AutonomyLevel.A2_REVERSIBLE), Set.of("com.example.shop"), Set.of(),
                new Budget(maxActions, Duration.ofSeconds(10), 4, 5));
    }

    private static TargetHandle target() { return new TargetHandle("target", 1, "task", "com.example.shop"); }

    private static final class NoHuman implements HumanGateway {
        @Override public ConfirmationToken confirm(ConfirmationRequest request) throws HumanUnavailableException { throw new HumanUnavailableException("no human"); }
        @Override public HandoffSession handoff(HandoffRequest request) throws HumanUnavailableException { throw new HumanUnavailableException("no human"); }
        @Override public void endHandoff(HandoffSession session) { }
        @Override public void pause(String reason) { }
        @Override public void resume() { }
        @Override public void stop(String reason) { }
    }
}
