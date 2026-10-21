package agent.next.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import agent.next.brain.DefaultRiskGate;
import agent.next.app.skill.RiskGuardPolicy;
import agent.next.app.skill.BrowserResultVerifier;
import agent.next.app.skill.BrowserTaskPlan;
import agent.next.app.security.EncryptedEpisodicMemory;
import agent.next.app.security.EncryptedModelConfigStore;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.TaskExecutionControl;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.DeepSeekVisualModel;
import agent.next.brain.model.DeepSeekReactiveModel;
import agent.next.brain.action.VisualTapResolver;
import agent.next.brain.reactive.ReactiveDecision;
import agent.next.brain.reactive.ReactiveSessionRunner;
import agent.next.brain.model.ModelCallBudget;
import agent.next.brain.model.ModelConfig;
import agent.next.brain.model.StageExtractRequest;
import agent.next.brain.plan.DeepSeekTaskPlanner;
import agent.next.brain.contract.TaskClarification;
import agent.next.brain.contract.TaskContract;
import agent.next.brain.contract.TaskReport;
import agent.next.brain.contract.TaskReportBuilder;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.PlanningResult;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.session.DeviceTargetBinder;
import agent.next.brain.session.AutomaticTaskFlow;
import agent.next.brain.session.SessionResult;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.brain.session.TargetBinder;
import agent.next.brain.session.TargetFrameSource;
import agent.next.brain.session.VisualSubgoalVerifier;
import agent.next.device.DeviceException;
import agent.next.device.DisplayHandle;
import agent.next.device.Frame;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;
import android.view.Surface;
import agent.next.device.UiNode;
import agent.phone.capability.BlockingDeviceApi;
import agent.phone.connection.ShizukuConnectionDriver;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide task runtime: natural-language tasks, execution, stop, and UI callbacks. */
public final class AgentRuntime {

    private static final String TAG = "AgentNextRuntime";
    private static volatile AgentRuntime instance;

    public enum State {
        IDLE,
        PLANNING,
        NEEDS_INPUT,
        RUNNING,
        STOPPING,
        DONE,
        PARTIAL,
        NEED_USER,
        STOPPED,
        FAILED
    }

    public interface Listener {
        void onState(State state, String detail);

        void onClarification(String question);

        void onSessionResult(SessionResult result);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private final InMemoryWorkingMemory memory = new InMemoryWorkingMemory();
    private final EncryptedEpisodicMemory episodic;
    private final UiHumanGateway human = new UiHumanGateway();
    private volatile Listener listener;
    private volatile State state = State.IDLE;
    private volatile String detail = "";
    private BlockingDeviceApi api;
    private DisplayHandle display;
    private volatile String clarificationQuestion = "";
    private TaskContract contract;
    private PlanningRequest planningRequest;
    private ChatCompletionClient client;
    private DeepSeekVisualModel visualModel;
    private TaskExecutionControl control;
    private TargetHandle activeTarget;
    private volatile boolean demoMonitoringEnabled;
    private volatile DemoFramePublisher demoFrames;
    private final Object monitoringLock = new Object();

    private AgentRuntime(Context context) {
        this.context = context.getApplicationContext();
        this.episodic = new EncryptedEpisodicMemory(this.context);
    }

    public static AgentRuntime get(Context context) {
        AgentRuntime existing = instance;
        if (existing == null) {
            synchronized (AgentRuntime.class) {
                existing = instance;
                if (existing == null) {
                    existing = new AgentRuntime(context);
                    instance = existing;
                }
            }
        }
        return existing;
    }

    public void clearMemory() {
        episodic.clear();
    }

    public int memoryEventCount() {
        return episodic.size();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            State snapshot = state;
            String message = detail;
            main.post(() -> {
                if (this.listener == listener) {
                    listener.onState(snapshot, message);
                    if (snapshot == State.NEEDS_INPUT && !clarificationQuestion.isBlank()) {
                        listener.onClarification(clarificationQuestion);
                    }
                }
            });
        }
    }

    public boolean demoMonitoringEnabled() {
        return demoMonitoringEnabled;
    }

    public void setDemoMonitoringEnabled(boolean enabled) {
        if ((context.getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return;
        }
        demoMonitoringEnabled = enabled;
        previewWorker.execute(() -> {
            if (enabled) {
                startMonitoring();
            } else {
                stopMonitoring();
            }
        });
    }

    private void startMonitoring() {
        synchronized (monitoringLock) {
            if (!demoMonitoringEnabled || demoFrames != null || api == null || display == null) {
                return;
            }
            try {
                demoFrames = new DemoFramePublisher(context, api, display);
                demoFrames.update(state.name(), detail);
            } catch (DeviceException | RuntimeException failure) {
                Log.w(TAG, "read-only monitor unavailable", failure);
            }
        }
    }

    private void stopMonitoring() {
        synchronized (monitoringLock) {
            DemoFramePublisher previous = demoFrames;
            demoFrames = null;
            if (previous != null) {
                previous.close();
            }
        }
    }

    public void setConfirmationPresenter(UiHumanGateway.Presenter presenter) {
        human.setPresenter(presenter);
    }

    public State state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    public boolean canPreview() {
        return activeTarget != null && display != null && api != null;
    }

    public TargetHandle activeTarget() {
        return activeTarget;
    }

    public void startTask(PlanningRequest request) {
        TaskExecutionControl taskControl;
        synchronized (this) {
            if (state == State.PLANNING || state == State.RUNNING || state == State.STOPPING
                    || handoffLatch != null) {
                return;
            }
            contract = null;
            planningRequest = request;
            clarificationQuestion = "";
            taskControl = new TaskExecutionControl();
            control = taskControl;
            state = State.PLANNING;
            detail = "正在理解任务";
        }
        AgentTaskService.start(context);
        postState(State.PLANNING, "正在理解任务");
        worker.execute(() -> prepareAndRun(request, taskControl));
    }

    public void answerClarification(String answer) {
        PlanningRequest request;
        synchronized (this) {
            if (state != State.NEEDS_INPUT || planningRequest == null || answer == null || answer.isBlank()) return;
            request = planningRequest;
        }
        startTask(new PlanningRequest(request.taskId(),
                request.goal() + "\n用户补充信息：" + answer.trim(), request.constraints(),
                request.allowedApps(), request.installedApps(), request.primaryPackage(),
                request.deniedActions(), request.allowedVerifiers(), request.autonomy()));
    }

    public boolean waitingForHandoff() { return handoffLatch != null; }

    public boolean isPaused() {
        TaskExecutionControl current = control;
        return current != null && current.paused();
    }

    public boolean isUserPaused() {
        TaskExecutionControl current = control;
        return current != null && current.pausedBy("user");
    }

    public void pauseTask() {
        TaskExecutionControl current = control;
        if (current != null && (state == State.RUNNING || state == State.PLANNING)) {
            current.pause("user");
            postState(state, "已暂停");
        }
    }

    public void resumeTask() {
        TaskExecutionControl current = control;
        if (current != null) {
            current.resume("user");
            postState(state, current.paused() ? "正在接管，任务保持暂停" : "正在继续任务");
        }
    }

    private volatile java.util.concurrent.CountDownLatch handoffLatch;
    private volatile boolean handoffApproved;

    /** Requests help without switching the user's screen. Takeover is a separate user action. */
    public boolean handoffToUser(String reason) {
        BlockingDeviceApi currentApi = api;
        TargetHandle target = activeTarget;
        DisplayHandle currentDisplay = display;
        if (currentApi == null || target == null || currentDisplay == null) {
            return false;
        }
        try {
            java.util.concurrent.CountDownLatch latch =
                    new java.util.concurrent.CountDownLatch(1);
            handoffLatch = latch;
            handoffApproved = false;
            Log.i(TAG, "handoff requested: " + reason);
            postState(State.NEED_USER, "需要你处理：" + reason);
            AgentTaskService.start(context);
            AgentTaskService.handoff(context, reason);
            if (!latch.await(15, java.util.concurrent.TimeUnit.MINUTES)) {
                return false;
            }
            if (!handoffApproved) {
                Log.i(TAG, "handoff: not approved");
                return false;
            }
            // Returning control must first confirm the interactive mirror is gone and the
            // target is muted again; otherwise the agent would resume audibly.
            currentApi.stopPreview(currentDisplay);
            currentApi.tasks().moveToPrivate(target, currentDisplay);
            currentApi.observations().observe(target, ObservationOptions.defaults());
            Log.i(TAG, "handoff: returned to private display, audio muted");
            postState(State.RUNNING, "正在重新观察页面");
            return true;
        } catch (Throwable failure) {
            Log.w(TAG, "handoff failed: " + failure);
            return false;
        } finally {
            handoffLatch = null;
            AgentTaskService.finishHandoff(context);
        }
    }

    public void resumeHandoff() {
        handoffApproved = true;
        java.util.concurrent.CountDownLatch latch = handoffLatch;
        Log.i(TAG, "handoff resume requested, waiting=" + (latch != null));
        if (latch != null) {
            latch.countDown();
        }
    }

    public void stop() {
        java.util.concurrent.CountDownLatch latch = handoffLatch;
        if (latch != null) {
            handoffApproved = false;
            latch.countDown();
        }
        TaskExecutionControl current = control;
        if (current != null) {
            current.stop();
        }
        postState(State.STOPPING, "正在停止");
        worker.execute(() -> {
            if (state == State.STOPPING) {
                postState(State.STOPPED, "用户停止任务");
                retainDeviceForTakeover();
            }
        });
    }

    @FunctionalInterface
    public interface PreviewCallback {
        void done(boolean ok, String message);
    }

    public void pauseForUser() {
        TaskExecutionControl current = control;
        if (current != null && (state == State.RUNNING || state == State.PLANNING || handoffLatch != null)) {
            current.pause("preview");
            detail = "用户接管，agent 已暂停";
            postState(state, detail);
        }
    }

    public void resumeAfterUser() {
        TaskExecutionControl current = control;
        if (current != null && current.pausedBy("preview")) {
            current.resume("preview");
            detail = current.paused() ? "已暂停" : "正在继续任务";
            postState(state, detail);
        }
    }

    public void startPreview(Surface surface, PreviewCallback callback) {
        BlockingDeviceApi currentApi = api;
        DisplayHandle currentDisplay = display;
        if (currentApi == null || currentDisplay == null || surface == null
                || !surface.isValid()) {
            finish(callback, false, "没有可预览的私有 display");
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.awaitIdleForPreview();
                currentApi.startPreview(currentDisplay, surface);
                finish(callback, true, "preview mirror started");
            } catch (Throwable failure) {
                finish(callback, false, "preview start failed: " + failure.getMessage());
            }
        });
    }

    /** Opens a passive view of the private display without pausing or taking input ownership. */
    public void startReadOnlyPreview(Surface surface, PreviewCallback callback) {
        BlockingDeviceApi currentApi = api;
        DisplayHandle currentDisplay = display;
        if (currentApi == null || currentDisplay == null || surface == null
                || !surface.isValid()) {
            finish(callback, false, "没有可预览的私有 display");
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.awaitIdleForPreview();
                currentApi.startPhoneReadOnlyMirror(currentDisplay, surface);
                finish(callback, true, "只读预览已打开，Agent继续运行");
            } catch (Throwable failure) {
                finish(callback, false, "只读预览启动失败：" + failure.getMessage());
            }
        });
    }

    public void stopReadOnlyPreview(PreviewCallback callback) {
        BlockingDeviceApi currentApi = api;
        DisplayHandle currentDisplay = display;
        if (currentApi == null || currentDisplay == null) {
            finish(callback, true, "没有活动的只读预览");
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.stopPhoneReadOnlyMirror(currentDisplay);
                finish(callback, true, "只读预览已关闭，Agent保持运行");
            } catch (Throwable failure) {
                finish(callback, false, "只读预览关闭失败：" + failure.getMessage());
            }
        });
    }

    public void stopPreview() {
        stopPreview(null);
    }

    /** Stops the interactive mirror and reports only after re-muting was confirmed. */
    public void stopPreview(PreviewCallback callback) {
        BlockingDeviceApi currentApi = api;
        DisplayHandle currentDisplay = display;
        if (currentApi == null || currentDisplay == null) {
            finish(callback, true, "no active preview");
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.stopPreview(currentDisplay);
                if (state == State.RUNNING || state == State.NEEDS_INPUT
                        || state == State.DONE && canPreview()) {
                    startMonitoring();
                }
                finish(callback, true, "preview stopped; target audio muted");
            } catch (Throwable failure) {
                Log.w(TAG, "preview stop unconfirmed: " + failure.getMessage());
                finish(callback, false, "preview stop/mute unconfirmed: " + failure.getMessage());
            }
        });
    }

    /** Stops any active task and releases the retained screen after queued task work has ended. */
    public void releaseRetainedScreen(PreviewCallback callback) {
        TaskExecutionControl current = control;
        if (current != null && (state == State.PLANNING || state == State.RUNNING
                || state == State.STOPPING || handoffLatch != null)) {
            stop();
        }
        worker.execute(() -> {
            try {
                BlockingDeviceApi currentApi = api;
                DisplayHandle currentDisplay = display;
                if (currentApi != null && currentDisplay != null) {
                    currentApi.stopPreview(currentDisplay);
                }
                closeDevice();
                finish(callback, true, "保留画面已释放");
            } catch (Throwable failure) {
                Log.w(TAG, "retained screen release unconfirmed: " + failure.getMessage());
                finish(callback, false, "释放画面失败：" + failure.getMessage());
            }
        });
    }

    public void previewTap(int x, int y, PreviewCallback callback) {
        BlockingDeviceApi currentApi = api;
        TargetHandle currentTarget = activeTarget;
        if (currentApi == null || currentTarget == null) {
            finish(callback, false, "没有活动 target");
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.userTap(currentTarget, x, y);
                finish(callback, true, "user tap " + x + "," + y);
            } catch (Throwable failure) {
                finish(callback, false, "user tap failed: " + failure.getMessage());
            }
        });
    }

    public void previewSwipe(int x1, int y1, int x2, int y2, int durationMs,
            PreviewCallback callback) {
        BlockingDeviceApi currentApi = api;
        TargetHandle currentTarget = activeTarget;
        if (currentApi == null || currentTarget == null) {
            finish(callback, false, "没有活动 target");
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.userSwipe(currentTarget, x1, y1, x2, y2, durationMs);
                finish(callback, true, "user swipe");
            } catch (Throwable failure) {
                finish(callback, false, "user swipe failed: " + failure.getMessage());
            }
        });
    }

    public void moveToMain(PreviewCallback callback) {
        BlockingDeviceApi currentApi = api;
        TargetHandle currentTarget = activeTarget;
        if (currentApi == null || currentTarget == null) {
            finish(callback, false, "没有活动 target");
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.tasks().moveToMain(currentTarget);
                finish(callback, true, "target moved to main screen");
            } catch (Throwable failure) {
                finish(callback, false, "move to main failed: " + failure.getMessage());
            }
        });
    }

    public void moveToPrivate(PreviewCallback callback) {
        BlockingDeviceApi currentApi = api;
        TargetHandle currentTarget = activeTarget;
        DisplayHandle currentDisplay = display;
        if (currentApi == null || currentTarget == null || currentDisplay == null) {
            finish(callback, false, "没有活动 target/display");
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.tasks().moveToPrivate(currentTarget, currentDisplay);
                finish(callback, true, "target returned to private display");
            } catch (Throwable failure) {
                finish(callback, false, "return to private failed: " + failure.getMessage());
            }
        });
    }

    private void finish(PreviewCallback callback, boolean ok, String message) {
        if (callback != null) {
            main.post(() -> callback.done(ok, message));
        }
    }

    public void close() {
        TaskExecutionControl current = control;
        if (current != null) current.stop();
        worker.execute(this::closeDevice);
        state = State.IDLE;
        detail = "";
        clarificationQuestion = "";
        control = null;
    }

    private void prepareAndRun(PlanningRequest request, TaskExecutionControl taskControl) {
        try {
            memory.clear();
            taskControl.checkpoint();
            if (activeTarget != null) closeDevice();
            Optional<ModelConfig> config = EncryptedModelConfigStore.load(context);
            if (config.isEmpty()) {
                fail("请先在设置中配置模型连接");
                return;
            }
            client = new ChatCompletionClient(config.get(), new ModelCallBudget(80));
            visualModel = new DeepSeekVisualModel(client);
            DeepSeekTaskPlanner planner = new DeepSeekTaskPlanner(client);
            AutomaticTaskFlow flow = new AutomaticTaskFlow(candidate -> {
                Optional<String> missing = TaskClarification.detect(candidate.goal(),
                        LocalDate.now(), ZoneId.systemDefault());
                return missing.isPresent() ? PlanningResult.clarify(missing.orElseThrow())
                        : planner.planReactive(candidate);
            });
            flow.run(request, taskControl, question -> {
                clarificationQuestion = question;
                postState(State.NEEDS_INPUT, question);
                Listener current = listener;
                if (current != null) main.post(() -> {
                    if (listener == current && state == State.NEEDS_INPUT) current.onClarification(question);
                });
            }, (prepared, validated) -> {
                taskControl.checkpoint();
                contract = validated;
                publishProgress("正在连接设备");
                ensureDevice();
                taskControl.checkpoint();
                postState(State.RUNNING, "正在观察当前页面");
                doRun(prepared);
            });
        } catch (LoopStoppedException stopped) {
            if (control == taskControl) {
                postState(State.STOPPED, stopped.getMessage());
                retainDeviceForTakeover();
            }
        } catch (Throwable failure) {
            Log.e(TAG, "task preparation failed", failure);
            if (control == taskControl) fail("无法开始任务：" + failure.getMessage());
        }
    }

    private void publishProgress(String message) {
        if (!isPaused() && state != State.STOPPING && state != State.STOPPED && handoffLatch == null) {
            postState(state, message);
        }
    }

    private void publishDecisionProgress(ReactiveDecision decision) {
        if (decision instanceof ReactiveDecision.Act act) {
            if (!act.progress().isBlank()) publishProgress(act.progress());
            else if (act.proposal() instanceof agent.next.brain.action.ActionProposal.SetText) publishProgress("正在填写信息");
            else if (act.proposal() instanceof agent.next.brain.action.ActionProposal.Swipe) publishProgress("正在浏览页面");
            else if (act.proposal() instanceof agent.next.brain.action.ActionProposal.Wait) publishProgress("正在等待页面加载");
            else publishProgress("正在操作当前页面");
        } else if (decision instanceof ReactiveDecision.OpenApp) publishProgress("正在打开目标应用");
        else if (decision instanceof ReactiveDecision.Done || decision instanceof ReactiveDecision.CheckResult) publishProgress("正在核验任务结果");
    }

    private void doRun(TaskPlan confirmed) {
        TaskExecutionControl runControl = control;
        String originalGoal = planningRequest == null ? confirmed.task().goal()
                : planningRequest.goal();
        boolean scopedBrowser = BrowserTaskPlan.supportsGoal(originalGoal)
                && confirmed.subgoals().stream()
                        .allMatch(s -> s.targetPackage().equals("com.quark.browser"));
        try {
            if (runControl == null) {
                fail("运行时控制未初始化");
                return;
            }
            TargetBinder delegate = new DeviceTargetBinder(api, display, confirmed.task());
            TargetBinder binder = new TargetBinder() {
                @Override
                public TargetHandle open(String packageName) throws DeviceException {
                    TargetHandle opened = delegate.open(packageName);
                    activeTarget = opened;
                    Log.i(TAG, "reactive target opened " + packageName);
                    return opened;
                }

                @Override
                public void release(TargetHandle target) throws DeviceException {
                    try {
                        delegate.release(target);
                    } finally {
                        if (activeTarget != null && activeTarget.token().equals(target.token())) {
                            activeTarget = null;
                        }
                    }
                }
            };
            TargetFrameSource frames = target -> api.observations().captureFrame(target);
            SubgoalVerifier visual = new VisualSubgoalVerifier(visualModel, frames);
            BrowserResultVerifier browserFinal = scopedBrowser
                    ? new BrowserResultVerifier(originalGoal, visualModel) : null;
            SubgoalVerifier finalVerifier = (task, outcome, target, observation) -> {
                publishProgress("正在核验任务结果");
                SubgoalVerifier.Verification checked = visual.verify(task, outcome, target, observation);
                if (checked.satisfied() && browserFinal != null) {
                    extractStage(task, target, observation, browserFinal);
                    return new SubgoalVerifier.Verification(browserFinal.satisfied(), browserFinal.evidence());
                }
                return checked;
            };
            DeepSeekReactiveModel actionModel = new DeepSeekReactiveModel(client);
            VisualTapResolver visualTapResolver = new ReactiveVisualTapLocator(client);
            ReactiveSessionRunner runner =
                    new ReactiveSessionRunner(api, confirmed.task(),
                            confirmed.subgoals().get(0).targetPackage(), binder,
                            request -> {
                                var boundary = RiskGuardPolicy.handoffReason(request.observation());
                                if (boundary.isPresent()) {
                                    var decision = new ReactiveDecision.NeedUser(boundary.orElseThrow());
                                    publishDecisionProgress(decision);
                                    return decision;
                                }
                                publishProgress("正在观察页面并决定下一步");
                                var decision = actionModel.next(request);
                                publishDecisionProgress(decision);
                                return decision;
                            }, frames,
                            finalVerifier, new DefaultRiskGate(), human, runControl,
                            this::handoffToUser, event -> Log.i(TAG, event), true, visualTapResolver);
            SessionResult result = runner.run();
            if (runControl.stopped()) {
                result = SessionResult.stopped("用户已停止任务", result.actions(), result.attempts(), "task", null);
            }
            if (result.status() == SessionResult.Status.DONE && browserFinal != null) {
                if (!browserFinal.satisfied()) {
                    result = SessionResult.partial("最终页面未核验：" + browserFinal.evidence(),
                            result.actions(), result.attempts(), "", null);
                } else {
                    result = new SessionResult(SessionResult.Status.DONE,
                            "标题：" + browserFinal.title()
                                    + "；证据：" + browserFinal.evidence(),
                            result.actions(), result.attempts(), "", null);
                }
            }
            if (contract != null) {
                TaskReport report = TaskReportBuilder.from(contract, confirmed, result);
                if (result.status() == SessionResult.Status.DONE && !report.done()) {
                    result = SessionResult.partial(
                            "task report: " + report.status() + " outcomes=" + report.outcomes(),
                            result.actions(), result.attempts(), "", null);
                }
            }
            SessionResult finalResult = result;
            synchronized (this) {
                state = switch (finalResult.status()) {
                    case DONE -> State.DONE;
                    case PARTIAL -> State.PARTIAL;
                    case NEED_USER -> State.NEED_USER;
                    case STOPPED -> State.STOPPED;
                    default -> State.FAILED;
                };
                detail = finalResult.detail();
            }
            postState(state, detail);
            Listener current = listener;
            if (current != null) {
                main.post(() -> current.onSessionResult(finalResult));
            }
        } catch (Throwable failure) {
            Log.e(TAG, "session failed", failure);
            fail("执行失败: " + failure.getMessage());
        } finally {
            retainDeviceForTakeover();
        }
    }

    private agent.next.brain.model.StageData extractStage(TaskSpec task, TargetHandle target,
            Observation observation, BrowserResultVerifier browserFinal) throws DeviceException {
        if (browserFinal != null) {
            browserFinal.invalidate("未获得新的最终页面证据");
            memory.put("stage.last.entity.title", "");
        }
        Frame frame = api.observations().captureFrame(target);
        List<String> text = new ArrayList<>();
        for (UiNode node : observation.nodes()) {
            String value = node.text() == null ? "" : node.text().trim();
            if (!value.isEmpty() && text.size() < 80) {
                text.add(value.length() > 200 ? value.substring(0, 200) : value);
            }
        }
        try {
            String extractionTask = task.goal();
            if (browserFinal != null) {
                extractionTask += "\n以本次截图为准：只有实际内容正文页才把完整真实页面标题写入"
                        + " entities.title；首页、搜索建议或搜索结果列表不要返回 title。"
                        + "禁止根据任务目标、搜索词或界面树推测标题。";
            }
            var data = visualModel.extract(new StageExtractRequest(extractionTask, frame, text));
            if (browserFinal != null) {
                browserFinal.check(frame, data);
                if (!browserFinal.satisfied()) {
                    var entities = new java.util.LinkedHashMap<>(data.entities());
                    entities.remove("title");
                    return new agent.next.brain.model.StageData(data.summary(), entities);
                }
            }
            return data;
        } catch (java.io.IOException failure) {
            throw new DeviceException(DeviceException.Code.INTERNAL,
                    "stage extraction failed", failure);
        }
    }

    private void ensureDevice() throws DeviceException {
        if (api == null) {
            api = new BlockingDeviceApi(
                    listener -> new ShizukuConnectionDriver(context, next -> {
                        Log.i(TAG, "device=" + next.status + "/" + next.reason
                                + " display=" + next.displayOperation
                                + " target=" + next.targetOperation);
                        listener.onStateChanged(next);
                    }), 60_000L);
            api.connect();
        }
        if (display == null) {
            display = api.displays().create(agent.next.device.DisplaySpec.standard());
        }
        startMonitoring();
    }

    private void closeDevice() {
        Log.i(TAG, "closeDevice", new Exception("closeDevice caller"));
        stopMonitoring();
        TargetHandle target = activeTarget;
        activeTarget = null;
        if (api != null && target != null) {
            try {
                api.targets().release(target);
            } catch (Throwable ignored) {
            }
        }
        if (api != null && display != null) {
            try {
                api.displays().release(display);
            } catch (Throwable ignored) {
            }
        }
        if (api != null) {
            api.close();
        }
        api = null;
        display = null;
        // Keep foreground protection until mirror output and device cleanup have finished.
        // A newer queued plan may already have started the service again.
        main.post(() -> {
            switch (state) {
                case IDLE, DONE, PARTIAL, STOPPED, FAILED, NEED_USER -> AgentTaskService.stop(context);
                default -> { }
            }
        });
    }

    private void retainDeviceForTakeover() {
        stopMonitoring();
        if (api != null && display != null && activeTarget != null) {
            AgentTaskService.update(context, "任务已结束，画面已保留；点开即可查看并接管");
            return;
        }
        closeDevice();
    }

    private void fail(String message) {
        synchronized (this) {
            state = State.FAILED;
            detail = message;
        }
        postState(State.FAILED, message);
        retainDeviceForTakeover();
    }

    private void postState(State next, String message) {
        state = next;
        detail = message;
        DemoFramePublisher monitor = demoFrames;
        if (monitor != null) {
            monitor.update(next.name(), message);
        }
        if (next != State.IDLE) {
            AgentTaskService.update(context, message);
        }
        Listener current = listener;
        if (current != null) {
            main.post(() -> current.onState(next, message));
        }
    }

}
