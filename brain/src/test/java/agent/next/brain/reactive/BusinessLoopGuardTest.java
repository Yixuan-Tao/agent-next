package agent.next.brain.reactive;

import agent.next.brain.Budget;
import agent.next.brain.RiskClass;
import agent.next.brain.TaskSpec;
import agent.next.brain.action.ActionProposal;
import agent.next.brain.action.ObservedNodeSelector;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopFakeDevice;
import agent.next.brain.loop.NodeSelector;
import agent.next.brain.session.SessionResult;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.InputController;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static agent.next.brain.TestSupport.check;

/** Regression cases for non-idempotent writes and loops spanning several pages. */
final class BusinessLoopGuardTest {
    private static final BusinessEffect ADD = new BusinessEffect("add:shirt:black:xl:total1",
            "指定黑色XL商品在购物车中的总数量为1", "完整购物车已加载且指定黑色XL商品不存在");

    static void run() {
        confirmedWriteIsSkippedAndTaskContinues();
        unknownWriteIsNotRepeated();
        positiveAbsencePermitsOnlyOneRetry();
        distinctEntitiesAreIndependent();
        timeoutAfterInjectionKeepsRecord();
        failedLocationDoesNotClaimAWriteAttempt();
        explicitlyRejectedInputDoesNotClaimAWriteAttempt();
        recordsSurviveHistoryEviction();
        noProgressBudgetIsUsed();
        semanticRouteIgnoresNodeAndWindowRenumbering();
        appScopeAndRejectedReceipts();
        allVerifiedResultsFinishWithoutAnotherModelDecision();
        partialVerificationContinuesUntilEveryRequiredResult();
    }

    private static void partialVerificationContinuesUntilEveryRequiredResult() {
        TaskSpec base = ReactiveSessionTest.task(20);
        TaskSpec task = new TaskSpec(base.id(),base.goal(),base.constraints(),
                List.of(new agent.next.brain.SuccessCriterion("first","商品为目标商品",true),
                        new agent.next.brain.SuccessCriterion("second","数量为1",true)),
                base.autonomy(),base.allowedApps(),base.deniedActions(),base.budget());
        AtomicInteger decisions = new AtomicInteger();
        SessionResult result = ReactiveSessionTest.run(new LoopFakeDevice(),task,request -> {
            int decision = decisions.incrementAndGet();
            return new ReactiveDecision.CheckResult(List.of(decision == 1 ? "first" : "second"));
        },LoopControl.none(),new ArrayList<>(),(spec,goal,target,observation) -> verified(true));
        check(result.status() == SessionResult.Status.DONE && decisions.get() == 2,
                "a successful subset must not finish the task before all required outcomes are verified");
    }

    private static void allVerifiedResultsFinishWithoutAnotherModelDecision() {
        AtomicInteger decisions = new AtomicInteger();
        AtomicInteger checks = new AtomicInteger();
        TaskSpec task = ReactiveSessionTest.task(20);
        SessionResult result = ReactiveSessionTest.run(new LoopFakeDevice(),task,request -> {
            decisions.incrementAndGet();
            return new ReactiveDecision.CheckResult(task.successCriteria().stream()
                    .filter(c -> c.required()).map(c -> c.id()).toList());
        },LoopControl.none(),new ArrayList<>(),(spec,goal,target,observation) -> {
            checks.incrementAndGet();
            return verified(true);
        });
        check(result.status() == SessionResult.Status.DONE && decisions.get() == 1 && checks.get() == 1,
                "independently verified required outcomes must finish immediately without another Done or repeated check");
    }

    private static void confirmedWriteIsSkippedAndTaskContinues() {
        LoopFakeDevice api = new LoopFakeDevice(30);
        AtomicInteger checks = new AtomicInteger();
        List<String> events = new ArrayList<>();
        SessionResult result = ReactiveSessionTest.run(api, ReactiveSessionTest.task(20), request -> {
            if (request.turn() < 2) return tap(request, ADD);
            if (request.turn() == 2) return tap(request, null); // A different navigation after duplicate feedback.
            return new ReactiveDecision.Done("verified task result");
        }, LoopControl.none(), events, (task, goal, target, observation) -> {
            checks.incrementAndGet();
            return verified(true);
        });
        check(result.status() == SessionResult.Status.DONE && api.taps() == 2 && checks.get() == 2,
                "confirmed add is skipped while a different navigation and final verification still work");
        check(events.stream().anyMatch(s -> s.contains("阻止重复副作用")), "duplicate feedback is visible to the next decision");
    }

    private static void unknownWriteIsNotRepeated() {
        LoopFakeDevice api = new LoopFakeDevice(30);
        AtomicInteger checks = new AtomicInteger();
        SessionResult result = ReactiveSessionTest.run(api, ReactiveSessionTest.task(20), request -> tap(request, ADD),
                LoopControl.none(), new ArrayList<>(), (task, goal, target, observation) -> {
                    checks.incrementAndGet();
                    return verified(false);
                });
        check(result.status() == SessionResult.Status.FAILED && api.taps() == 1,
                "unknown result cannot cause another add even on a changed page");
        check(checks.get() == 2, "same evidence is not repeatedly sent to independent verification");
    }

    private static void positiveAbsencePermitsOnlyOneRetry() {
        LoopFakeDevice api = new LoopFakeDevice(30);
        SessionResult result = ReactiveSessionTest.run(api, ReactiveSessionTest.task(20), request -> tap(request, ADD),
                LoopControl.none(), new ArrayList<>(), (task, goal, target, observation) ->
                        verified(goal.postconditions().get(0).description().equals(ADD.absenceCondition())));
        check(result.status() == SessionResult.Status.FAILED && api.taps() == 2,
                "independent positive absence allows one retry but never a third write");
    }

    private static void distinctEntitiesAreIndependent() {
        LoopFakeDevice api = new LoopFakeDevice(30);
        BusinessEffect other = new BusinessEffect("add:shirt:white:m:total1", "白色M商品总数1", "完整购物车无白色M商品");
        SessionResult result = ReactiveSessionTest.run(api, ReactiveSessionTest.task(20), request -> switch (request.turn()) {
            case 0 -> tap(request, ADD);
            case 1 -> tap(request, other);
            default -> new ReactiveDecision.Done("two distinct requested entities");
        }, LoopControl.none(), new ArrayList<>(), (task, goal, target, observation) -> verified(true));
        check(result.status() == SessionResult.Status.DONE && api.taps() == 2,
                "different task objects do not collide even when their buttons have the same label");
    }

    private static void timeoutAfterInjectionKeepsRecord() {
        LoopFakeDevice underlying = new LoopFakeDevice(30);
        InputController input = (InputController) Proxy.newProxyInstance(InputController.class.getClassLoader(),
                new Class<?>[] {InputController.class}, (proxy, method, args) -> {
                    Object value = invoke(underlying.input(), method, args);
                    if (method.getName().equals("tap")) throw new DeviceException(DeviceException.Code.TIMEOUT, "receipt lost after injection");
                    return value;
                });
        DeviceApi api = (DeviceApi) Proxy.newProxyInstance(DeviceApi.class.getClassLoader(), new Class<?>[] {DeviceApi.class},
                (proxy, method, args) -> method.getName().equals("input") ? input : invoke(underlying, method, args));
        SessionResult result = ReactiveSessionTest.run(api, ReactiveSessionTest.task(20), request -> tap(request, ADD),
                LoopControl.none(), new ArrayList<>(), (task, goal, target, observation) -> verified(false));
        check(result.status() == SessionResult.Status.FAILED && underlying.taps() == 1,
                "lost receipt after real input retains the pending write instead of blindly retrying");
    }

    private static void failedLocationDoesNotClaimAWriteAttempt() {
        LoopFakeDevice api = new LoopFakeDevice(30);
        AtomicInteger checks = new AtomicInteger();
        SessionResult result = ReactiveSessionTest.run(api,ReactiveSessionTest.task(20),request -> {
            if (request.turn() == 0) {
                return new ReactiveDecision.Act(new ActionProposal.Tap("missing-add","locate add button",
                        NodeSelector.textEquals("missing control"),RiskClass.LOW),"加入购物车","",ADD);
            }
            if (request.turn() == 1) {
                check(request.executionState().stream().noneMatch(s -> s.contains(ADD.key())),
                        "a location failure before input must not send the model to verify a nonexistent add attempt");
                return tap(request,ADD);
            }
            return new ReactiveDecision.Done("actual write now injected");
        },LoopControl.none(),new ArrayList<>(),(task,goal,target,observed) -> {
            checks.incrementAndGet();
            return verified(true);
        });
        check(result.status() == SessionResult.Status.DONE && api.taps() == 1 && checks.get() == 1,
                "the corrected add must execute directly instead of being blocked as a duplicate");
    }

    private static void explicitlyRejectedInputDoesNotClaimAWriteAttempt() {
        for (DeviceException.Code code : List.of(DeviceException.Code.STALE,DeviceException.Code.REJECTED,DeviceException.Code.BUSY)) {
            LoopFakeDevice underlying = new LoopFakeDevice(30);
            AtomicInteger attempts = new AtomicInteger();
            InputController input = (InputController) Proxy.newProxyInstance(InputController.class.getClassLoader(),
                    new Class<?>[] {InputController.class},(proxy,method,args) -> {
                        if (method.getName().equals("tap") && attempts.getAndIncrement() == 0) {
                            throw new DeviceException(code,"input rejected before injection");
                        }
                        return invoke(underlying.input(),method,args);
                    });
            DeviceApi api = (DeviceApi) Proxy.newProxyInstance(DeviceApi.class.getClassLoader(),new Class<?>[] {DeviceApi.class},
                    (proxy,method,args) -> method.getName().equals("input") ? input : invoke(underlying,method,args));
            SessionResult result = ReactiveSessionTest.run(api,ReactiveSessionTest.task(20),request -> {
                if (request.turn() == 0) {
                    return new ReactiveDecision.Act(new ActionProposal.Tap("first-add","add current product",
                            new ObservedNodeSelector(request.observation(),2),RiskClass.LOW),"加入购物车","",ADD);
                }
                if (request.turn() == 1) {
                    check(request.executionState().stream().noneMatch(s -> s.contains(ADD.key())),
                            "explicit input rejection must not claim an injected write: " + code);
                    return tap(request,ADD);
                }
                return new ReactiveDecision.Done("add injected after new observation");
            },LoopControl.none(),new ArrayList<>(),(task,goal,target,observed) -> verified(true));
            check(result.status() == SessionResult.Status.DONE && underlying.taps() == 1,
                    "explicitly rejected input can be corrected without false duplicate protection: " + code);
        }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    private static void recordsSurviveHistoryEviction() {
        LoopFakeDevice api = new LoopFakeDevice(30);
        SessionResult result = ReactiveSessionTest.run(api, ReactiveSessionTest.task(25), request -> {
            if (request.turn() == 0) return tap(request, ADD);
            if (request.turn() < 15) return tap(request, null);
            if (request.turn() == 15) {
                check(request.history().size() == 12 && request.history().stream().noneMatch(s -> s.contains("登记副作用")),
                        "the original write has left the short history");
                check(request.executionState().stream().anyMatch(s -> s.contains(ADD.key()) && s.contains("尝试=1")),
                        "durable execution state still includes that write");
                return tap(request, ADD);
            }
            return new ReactiveDecision.Done("result verified after long navigation");
        }, LoopControl.none(), new ArrayList<>(), (task, goal, target, observation) -> verified(true));
        check(result.status() == SessionResult.Status.DONE && api.taps() == 15,
                "history eviction cannot cause an extra add");
    }

    private static void noProgressBudgetIsUsed() {
        TaskSpec base = ReactiveSessionTest.task(20);
        TaskSpec task = new TaskSpec(base.id(), base.goal(), base.constraints(), base.successCriteria(), base.autonomy(),
                base.allowedApps(), base.deniedActions(), new Budget(20, Duration.ofSeconds(10), 100, 2));
        List<String> events = new ArrayList<>();
        SessionResult result = ReactiveSessionTest.run(new LoopFakeDevice(), task, request ->
                request.observation().activityName().equals("fake.Results")
                        ? new ReactiveDecision.Act(new ActionProposal.Back("back", "return", RiskClass.LOW)) : tap(request, null),
                LoopControl.none(), events, (spec, goal, target, observation) -> verified(false));
        check(result.status() == SessionResult.Status.FAILED && result.actions() <= 4
                        && events.stream().anyMatch(s -> s.contains("连续无进展=2")),
                "changing between old pages uses the configured no-progress threshold");
    }

    private static void semanticRouteIgnoresNodeAndWindowRenumbering() {
        try {
            Observation original = new LoopFakeDevice().observations().observe(new TargetHandle("target", 1, "task", "com.example.shop"), ObservationOptions.defaults());
            List<UiNode> nodes = new ArrayList<>();
            for (UiNode n : original.nodes()) nodes.add(new UiNode(n.ref(), n.index() + 10,
                    n.parentIndex() < 0 ? -1 : n.parentIndex() + 10, n.windowId() + 1,
                    n.viewId(), n.className(), n.text(), n.contentDescription(), n.bounds(), n.flags(), n.textTruncated()));
            Observation changed = new Observation(original.status(), original.reference().orElseThrow(), original.packageName(),
                    original.activityName(), original.displayId(), original.windowId() + 1, true, original.capturedAt(),
                    original.lookupCount(), original.truncated(), original.windowBounds().orElseThrow(), nodes);
            ReactiveLoopGuard guard = new ReactiveLoopGuard(Budget.defaults());
            ActionProposal.Tap tap = new ActionProposal.Tap("a", "a", NodeSelector.textEquals("Search"), RiskClass.LOW);
            guard.observe(original, false);
            guard.executed(original, tap);
            guard.executed(original, tap);
            guard.observe(changed, true);
            check(!guard.block(changed, tap).isEmpty(), "node and window renumbering cannot disguise the same route");
        } catch (DeviceException failure) { throw new AssertionError(failure); }
    }

    private static void appScopeAndRejectedReceipts() {
        try {
            EffectLedger ledger = new EffectLedger();
            ledger.attempted("app.one", ADD);
            check(ledger.authorize("app.two", ADD, "page", condition -> verified(false)).allowed(), "effect keys are scoped to each app");
            ledger.notInjected("app.one", ADD);
            check(ledger.authorize("app.one", ADD, "page", condition -> verified(false)).allowed(), "explicit rejection does not retain an injected-write claim");
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static ReactiveDecision.Act tap(ReactiveRequest request, BusinessEffect effect) {
        return new ReactiveDecision.Act(new ActionProposal.Tap("tap", "tap actual current control",
                new ObservedNodeSelector(request.observation(), 2), RiskClass.LOW), "读取实际结果", "", effect);
    }

    private static SubgoalVerifier.Verification verified(boolean value) {
        return new SubgoalVerifier.Verification(value, value ? "independent visible evidence" : "not proven");
    }
}
