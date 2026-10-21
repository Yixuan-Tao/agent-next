package agent.next.brain.reactive;

import agent.next.brain.Condition;
import agent.next.brain.ConfirmationRejectedException;
import agent.next.brain.Fallback;
import agent.next.brain.HumanGateway;
import agent.next.brain.HumanUnavailableException;
import agent.next.brain.RiskClass;
import agent.next.brain.RiskGate;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.action.ActionExecutor;
import agent.next.brain.action.ActionProposal;
import agent.next.brain.action.VisualTapResolver;
import agent.next.brain.loop.HumanHandoffRequiredException;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.brain.loop.ObservationFingerprint;
import agent.next.brain.session.SessionResult;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.brain.session.TargetBinder;
import agent.next.brain.session.TargetFrameSource;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.ObservationStatus;
import agent.next.device.TargetHandle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** A task-level observe/decide/act loop. Only an independent final verifier can finish it. */
public final class ReactiveSessionRunner {
    private static final long READY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private static final int HISTORY_SIZE = 12;

    private final DeviceApi api;
    private final TaskSpec task;
    private final String initialPackage;
    private final TargetBinder targets;
    private final ReactiveModel model;
    private final TargetFrameSource frames;
    private final SubgoalVerifier verifier;
    private final LoopControl control;
    private final Predicate<String> handoff;
    private final Consumer<String> events;
    private final ActionExecutor executor;
    private final boolean retainTargetOnTerminal;

    public ReactiveSessionRunner(DeviceApi api, TaskSpec task, String initialPackage,
            TargetBinder targets, ReactiveModel model, TargetFrameSource frames,
            SubgoalVerifier verifier, RiskGate riskGate, HumanGateway human, LoopControl control,
            Predicate<String> handoff, Consumer<String> events) {
        this(api, task, initialPackage, targets, model, frames, verifier, riskGate, human,
                control, handoff, events, false);
    }

    public ReactiveSessionRunner(DeviceApi api, TaskSpec task, String initialPackage,
            TargetBinder targets, ReactiveModel model, TargetFrameSource frames,
            SubgoalVerifier verifier, RiskGate riskGate, HumanGateway human, LoopControl control,
            Predicate<String> handoff, Consumer<String> events, boolean retainTargetOnTerminal) {
        this(api, task, initialPackage, targets, model, frames, verifier, riskGate, human,
                control, handoff, events, retainTargetOnTerminal, null);
    }

    public ReactiveSessionRunner(DeviceApi api, TaskSpec task, String initialPackage,
            TargetBinder targets, ReactiveModel model, TargetFrameSource frames,
            SubgoalVerifier verifier, RiskGate riskGate, HumanGateway human, LoopControl control,
            Predicate<String> handoff, Consumer<String> events, boolean retainTargetOnTerminal,
            VisualTapResolver visualTapResolver) {
        this.api = Objects.requireNonNull(api, "api");
        this.task = Objects.requireNonNull(task, "task");
        this.initialPackage = Objects.requireNonNull(initialPackage, "initialPackage");
        if (!task.allowedApps().contains(initialPackage)) throw new IllegalArgumentException("initial app outside scope");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.model = Objects.requireNonNull(model, "model");
        this.frames = Objects.requireNonNull(frames, "frames");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.control = Objects.requireNonNull(control, "control");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
        this.events = Objects.requireNonNull(events, "events");
        this.retainTargetOnTerminal = retainTargetOnTerminal;
        executor = new ActionExecutor(api, riskGate, human, control, visualTapResolver);
    }

    public SessionResult run() {
        long deadline = System.nanoTime() + task.budget().maxDuration().toNanos();
        int maxTurns = (int) Math.min(300L, task.budget().maxActions() * 3L + 12);
        int actions = 0, turns = 0, blocked = 0, modelErrors = 0;
        List<String> history = new ArrayList<>();
        Set<String> failedActions = new HashSet<>();
        Map<String, String> verifiedByApp = new HashMap<>();
        ReactiveLoopGuard loopGuard = new ReactiveLoopGuard(task.budget());
        EffectLedger effects = new EffectLedger();
        boolean actionPending = false;
        boolean completionHandoffReturned = false;
        int guardBlocked = 0;
        TargetHandle target = null;
        String pendingPage = null, pendingKey = null;
        boolean keepCompletedTarget = false;
        try {
            control.checkpoint();
            target = targets.open(initialPackage);
            while (System.nanoTime() < deadline && turns < maxTurns) {
                control.checkpoint();
                Observation observed = observeReady(target, deadline);
                String loopFeedback = loopGuard.observe(observed, actionPending);
                actionPending = false;
                if (!loopFeedback.isEmpty()) remember(history, loopFeedback);
                String page = ObservationFingerprint.of(observed).contentDigest();
                if (pendingPage != null) {
                    boolean changed = !pendingPage.equals(page);
                    if (!changed) failedActions.add(pendingKey);
                    remember(history, changed ? "上个动作后页面已变化；仍需按当前页面核对预期效果。"
                            : "上个动作后页面没有变化；不要在相同页面重复该动作，检查加载或换一个真实目标。");
                    pendingPage = null;
                }
                Frame frame;
                try {
                    frame = frames.capture(target);
                } catch (DeviceException failure) {
                    if (!retryable(failure)) throw failure;
                    remember(history, "本轮截图暂不可用：" + failure.code() + "；仅有节点证据。");
                    frame = null;
                }
                ReactiveDecision decision;
                try {
                    List<String> executionState = new ArrayList<>(effects.snapshot());
                    executionState.add(loopGuard.snapshot());
                    decision = Objects.requireNonNull(model.next(new ReactiveRequest(task, observed,
                            frame, history, turns++, executionState)), "model returned no decision");
                    modelErrors = 0;
                } catch (RuntimeException failure) {
                    remember(history, "决策未执行：" + failure.getMessage() + "。根据最新节点重新选择单个合法动作。");
                    if (++modelErrors >= 3) {
                        return SessionResult.failed("连续无法生成合法动作：" + failure.getMessage(), actions, turns, "task", null);
                    }
                    continue;
                }
                control.checkpoint();
                if (System.nanoTime() >= deadline) break;
                events.accept("reactive turn=" + turns + " decision=" + decision.getClass().getSimpleName()
                        + " package=" + observed.packageName() + " nodes=" + observed.nodes().size());
                String verificationState = decision instanceof ReactiveDecision.Done || decision instanceof ReactiveDecision.CheckResult
                        ? verifiedByApp.keySet().stream().sorted().collect(java.util.stream.Collectors.toList()).toString() : "";
                String key = page + ":" + verificationState
                        + ":" + decisionKey(decision);
                if (failedActions.contains(key)) {
                    remember(history, "阻止重复失败决策：" + decisionKey(decision)
                            + "。页面未变化，必须换方法；不要重发相同动作。");
                    if (++blocked >= 3) return SessionResult.failed("相同页面反复选择已失败动作，无法可靠继续", actions, turns, "task", null);
                    continue;
                }
                blocked = 0;
                if (decision instanceof ReactiveDecision.Failed failed) {
                    return SessionResult.failed(failed.reason(), actions, turns, "task", null);
                }
                if (decision instanceof ReactiveDecision.Done || decision instanceof ReactiveDecision.CheckResult) {
                    Set<String> selectedIds = decision instanceof ReactiveDecision.CheckResult check
                            ? Set.copyOf(check.criterionIds()) : task.successCriteria().stream()
                                    .filter(c -> c.required() && !verifiedByApp.containsKey(c.id()))
                                    .map(c -> c.id()).collect(java.util.stream.Collectors.toSet());
                    if (selectedIds.stream().anyMatch(id -> task.successCriteria().stream()
                            .noneMatch(c -> c.required() && c.id().equals(id)))) {
                        failedActions.add(key);
                        remember(history, "核验请求包含任务中不存在的最终条件。");
                        continue;
                    }
                    Observation fresh = observeReady(target, deadline);
                    SubgoalVerifier.Verification checked = selectedIds.isEmpty()
                            ? new SubgoalVerifier.Verification(true, "所有最终条件已有独立页面证据")
                            : verifier.verify(task, outcome(target, selectedIds), target, fresh);
                    events.accept("reactive final verified=" + checked.satisfied() + " evidence=" + checked.evidence());
                    if (checked.satisfied()) {
                        if (selectedIds.stream().anyMatch(id -> !verifiedByApp.containsKey(id))) loopGuard.verifiedProgress();
                        for (String id : selectedIds) verifiedByApp.put(id, target.packageName());
                        remember(history, "已独立核验最终条件：" + selectedIds + "；App=" + target.packageName()
                                + "；已核验条件总表=" + verifiedByApp.keySet());
                        boolean allRequiredVerified = task.successCriteria().stream()
                                .filter(c -> c.required()).allMatch(c -> verifiedByApp.containsKey(c.id()));
                        if (!allRequiredVerified) continue;
                        if (task.handoffOnVerifiedCompletion() && !completionHandoffReturned) {
                            String reason = "已核验到订单提交/支付边界，请接管处理；Agent 不会提交订单或付款。";
                            if (!handoff.test(reason)) {
                                return SessionResult.needUser(reason, actions, turns, "task", null);
                            }
                            completionHandoffReturned = true;
                            invalidateResults(verifiedByApp, target.packageName());
                            remember(history, "用户已接管支付边界并返回；重新观察实际结果，不假设订单或付款已完成。");
                            continue;
                        }
                        // The owning runtime closes the device or retains its final preview after this return.
                        keepCompletedTarget = true;
                        return new SessionResult(SessionResult.Status.DONE,
                                "已完成并核验：" + task.successCriteria().stream().filter(c -> c.required())
                                        .map(c -> c.description()).collect(java.util.stream.Collectors.joining("；")),
                                actions, turns, "task", null);
                    }
                    failedActions.add(key);
                    remember(history, "完成申请被独立核验否决：" + checked.evidence()
                            + "。继续按当前页面完成缺失结果，不能只重复 Done。");
                    continue;
                }
                if (decision instanceof ReactiveDecision.NeedUser needUser) {
                    if (!handoff.test(needUser.reason())) return SessionResult.needUser(needUser.reason(), actions, turns, "task", null);
                    invalidateResults(verifiedByApp, target.packageName());
                    remember(history, "用户已交接返回；重新观察，不假设交接步骤已成功。");
                    continue;
                }
                if (decision instanceof ReactiveDecision.OpenApp open) {
                    if (!task.allowedApps().contains(open.packageName()) || target.packageName().equals(open.packageName())) {
                        failedActions.add(key);
                        remember(history, "未切换 App：目标不在允许范围或已经是当前 App。");
                        continue;
                    }
                    if (actions >= task.budget().maxActions()) break;
                    String openFailure = loopGuard.blockOpen(observed, open.packageName());
                    if (!openFailure.isEmpty()) {
                        remember(history, openFailure);
                        if (++guardBlocked >= 3) return SessionResult.failed(openFailure, actions, turns, "task", null);
                        continue;
                    }
                    targets.release(target);
                    target = null;
                    target = targets.open(open.packageName());
                    actions++;
                    loopGuard.opened(observed, open.packageName());
                    guardBlocked = 0;
                    actionPending = true;
                    remember(history, "已切换至允许的 App：" + open.packageName() + "，重新读取该页面。");
                    continue;
                }
                if (actions >= task.budget().maxActions()) break;
                ReactiveDecision.Act action = (ReactiveDecision.Act) decision;
                ActionProposal proposal = action.proposal();
                BusinessEffect effect = action.effect();
                if (effect != null) {
                    TargetHandle currentTarget = target;
                    EffectLedger.Gate gate;
                    try {
                        gate = effects.authorize(target.packageName(), effect, page,
                                condition -> verifier.verify(task, effectOutcome(currentTarget, condition),
                                        currentTarget, observeReady(currentTarget, deadline)));
                    } catch (DeviceException failure) {
                        if (!retryable(failure)) throw failure;
                        remember(history, "副作用核验暂不可用：" + failure.code() + "；保留已尝试记录，禁止盲目重试。");
                        if (++guardBlocked >= 3) return SessionResult.failed("连续无法核对副作用结果，已停止重复操作", actions, turns, "task", null);
                        continue;
                    }
                    if (!gate.feedback().isEmpty()) remember(history, gate.feedback());
                    if (!gate.allowed()) {
                        if (++guardBlocked >= 3) return SessionResult.failed("重复副作用尚未安全解决，停止重试；" + gate.feedback(), actions, turns, "task", null);
                        continue;
                    }
                }
                String guardFailure = loopGuard.block(observed, proposal);
                if (!guardFailure.isEmpty()) {
                    remember(history, guardFailure);
                    if (++guardBlocked >= 3) return SessionResult.failed(guardFailure, actions, turns, "task", null);
                    continue;
                }
                // Verification can take time; never dispatch using evidence from before that call.
                control.checkpoint();
                if (System.nanoTime() >= deadline) break;
                if (proposal instanceof ActionProposal.Wait wait) {
                    pause(wait.millis(), deadline);
                    actions++;
                    actionPending = true;
                    guardBlocked = 0;
                    remember(history, "已等待 " + wait.millis() + "ms，接下来读取实际加载结果。");
                    continue;
                }
                java.util.concurrent.atomic.AtomicBoolean inputAttempted = new java.util.concurrent.atomic.AtomicBoolean();
                try {
                    String inputApp = target.packageName();
                    ActionExecutor.Execution execution = executor.execute(task, target, proposal,() -> {
                        if (effect != null) {
                            effects.attempted(inputApp,effect);
                            inputAttempted.set(true);
                            remember(history,"登记副作用尝试：" + effect.key()
                                    + "；已进入设备输入阶段，结果待核对，页面变化不会清除记录。");
                        }
                    });
                    // A new operation can change the app's previously verified result.
                    invalidateResults(verifiedByApp, target.packageName());
                    actions++;
                    events.accept("reactive action=" + proposal.id() + " target=" + execution.action().target()
                            + " accepted=" + execution.receipt().accepted());
                    remember(history, "执行 " + proposal.description() + "：accepted=" + execution.receipt().accepted()
                            + "；预期变化（尚未证实）=" + action.expectedChange() + "，注入回执不代表任务完成。");
                    if (!execution.receipt().accepted()) {
                        if (effect != null) effects.notInjected(target.packageName(), effect);
                        failedActions.add(key);
                    } else {
                        loopGuard.executed(observed, proposal);
                        actionPending = true;
                        guardBlocked = 0;
                        pendingPage = page;
                        pendingKey = key;
                    }
                } catch (HumanHandoffRequiredException | HumanUnavailableException | ConfirmationRejectedException handoffRequired) {
                    if (!handoff.test(handoffRequired.getMessage())) return SessionResult.needUser(handoffRequired.getMessage(), actions, turns, "task", null);
                    invalidateResults(verifiedByApp, target.packageName());
                    remember(history, "用户已处理交接，请依据新页面继续。");
                } catch (DeviceException failure) {
                    if (effect != null && inputAttempted.get()
                            && (failure.code() == DeviceException.Code.STALE
                            || failure.code() == DeviceException.Code.REJECTED
                            || failure.code() == DeviceException.Code.BUSY)) {
                        effects.notInjected(target.packageName(),effect);
                        remember(history,"设备明确拒绝了本次输入，未注入；撤销本次副作用尝试，不算已加购。重新定位后继续当前步骤。");
                    }
                    if (failure.code() == DeviceException.Code.NEED_USER) {
                        if (!handoff.test(failure.getMessage())) return SessionResult.needUser(failure.getMessage(), actions, turns, "task", null);
                        invalidateResults(verifiedByApp, target.packageName());
                    } else if (!retryable(failure)) {
                        throw failure;
                    } else {
                        if (failure.code() != DeviceException.Code.BUSY) failedActions.add(key);
                        remember(history, "动作未成功：" + failure.code() + " " + failure.getMessage()
                                + "；旧目标不能复用，重新根据当前节点选择。");
                    }
                }
            }
            return SessionResult.budget("达到任务时间、动作或决策预算；最终结果尚未核验", actions, turns, "task", null);
        } catch (LoopStoppedException stopped) {
            return SessionResult.stopped(stopped.getMessage(), actions, turns, "task", null);
        } catch (DeviceException failure) {
            if (failure.code() == DeviceException.Code.NEED_USER) return SessionResult.needUser(failure.getMessage(), actions, turns, "task", null);
            return SessionResult.failed("设备操作失败：" + failure.code() + " " + failure.getMessage(), actions, turns, "task", null);
        } catch (RuntimeException failure) {
            return SessionResult.failed("任务循环失败：" + failure.getMessage(), actions, turns, "task", null);
        } finally {
            if (target != null && !keepCompletedTarget && !retainTargetOnTerminal) {
                try { targets.release(target); }
                catch (DeviceException failure) { events.accept("reactive cleanup failed: " + failure.getMessage()); }
            }
        }
    }

    private Observation observeReady(TargetHandle target, long taskDeadline) throws DeviceException, LoopStoppedException {
        long deadline = Math.min(taskDeadline, System.nanoTime() + READY_TIMEOUT_NANOS);
        DeviceException last = null;
        do {
            control.checkpoint();
            try {
                Observation observed = api.observations().observe(target, ObservationOptions.defaults());
                if (!observed.packageName().equals(target.packageName())) {
                    throw new DeviceException(DeviceException.Code.PROTOCOL, "观察返回了其他 App 的页面");
                }
                if (observed.status() == ObservationStatus.READY && observed.stable()) return observed;
                if (observed.status() == ObservationStatus.FOREIGN_WINDOW
                        || observed.status() == ObservationStatus.FOREGROUND_CONFLICT) {
                    throw new DeviceException(DeviceException.Code.NEED_USER, "目标页面需要本人处理或正在由用户前台使用：" + observed.status());
                }
            } catch (DeviceException failure) {
                if (!retryable(failure)) throw failure;
                last = failure;
            }
            pause(200, deadline);
        } while (System.nanoTime() < deadline);
        throw new DeviceException(DeviceException.Code.TIMEOUT,
                "等待页面就绪超时" + (last == null ? "" : ": " + last.getMessage()));
    }

    private Subgoal outcome(TargetHandle target, Set<String> selectedIds) {
        List<Condition> conditions = task.successCriteria().stream().filter(c -> c.required() && selectedIds.contains(c.id()))
                .map(c -> new Condition(c.id(), c.description(), true)).collect(java.util.stream.Collectors.toList());
        return new Subgoal("task", task.goal(), Set.of(), List.of(), conditions,
                new Fallback(Fallback.Strategy.RETRY, "observe current page"), RiskClass.LOW,
                true, target.packageName(), "visual");
    }

    private Subgoal effectOutcome(TargetHandle target, String condition) {
        return new Subgoal("effect-check", "独立核对业务副作用；未知、加载或不完整页面不能证明条件成立",
                Set.of(), List.of(), List.of(new Condition("effect", condition, true)),
                new Fallback(Fallback.Strategy.RETRY, "observe actual result"), RiskClass.LOW,
                true, target.packageName(), "visual");
    }

    private static boolean retryable(DeviceException failure) {
        return failure.code() == DeviceException.Code.BUSY || failure.code() == DeviceException.Code.STALE
                || failure.code() == DeviceException.Code.REJECTED || failure.code() == DeviceException.Code.TIMEOUT;
    }

    private static void invalidateResults(Map<String, String> verifiedByApp, String packageName) {
        verifiedByApp.entrySet().removeIf(entry -> entry.getValue().equals(packageName));
    }

    private static String decisionKey(ReactiveDecision decision) {
        if (decision instanceof ReactiveDecision.Act act) return act.proposal().payloadHash();
        if (decision instanceof ReactiveDecision.OpenApp open) return "OpenApp:" + open.packageName();
        if (decision instanceof ReactiveDecision.CheckResult check) return "CheckResult:" + check.criterionIds().stream().sorted().collect(java.util.stream.Collectors.toList());
        return decision.getClass().getSimpleName();
    }

    private void remember(List<String> history, String value) {
        String bounded = value.length() > 900 ? value.substring(0, 900) : value;
        if (history.size() == HISTORY_SIZE) history.remove(0);
        history.add(bounded);
        events.accept("reactive feedback=" + bounded);
    }

    private void pause(long millis, long deadline) throws LoopStoppedException {
        long until = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
        while (System.nanoTime() < until) {
            control.checkpoint();
            try { Thread.sleep(Math.max(1, Math.min(100, TimeUnit.NANOSECONDS.toMillis(until - System.nanoTime())))); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LoopStoppedException("等待被中断");
            }
        }
    }
}
