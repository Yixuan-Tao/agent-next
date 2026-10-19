package agent.next.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import agent.next.brain.DefaultRiskGate;
import agent.next.app.calendar.CalendarPolicy;
import agent.next.app.skill.RiskGuardPolicy;
import agent.next.app.skill.JdAddPolicy;
import agent.next.app.skill.JdCartPolicy;
import agent.next.app.skill.JdCartVerifier;
import agent.next.app.skill.QuarkWeatherPolicy;
import agent.next.app.calendar.CalendarStore;
import agent.next.app.calendar.CalendarSubgoalVerifier;
import agent.next.app.security.EncryptedEpisodicMemory;
import agent.next.app.security.EncryptedModelConfigStore;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.SlowLoopDecision;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.DeepSeekSkillModel;
import agent.next.brain.model.DeepSeekVisualModel;
import agent.next.brain.model.ModelCallBudget;
import agent.next.brain.model.ModelConfig;
import agent.next.brain.skill.SkillModel;
import agent.next.brain.skill.SkillModelRequest;
import agent.next.brain.skill.SkillModelStep;
import agent.next.brain.model.StageExtractRequest;
import agent.next.brain.plan.DeepSeekTaskPlanner;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.plan.PlanningResult;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.session.DeviceTargetBinder;
import agent.next.brain.session.PromptSessionPolicyFactory;
import agent.next.brain.session.SessionPolicyFactory;
import agent.next.brain.session.SessionResult;
import agent.next.brain.session.SessionRunner;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.brain.session.TargetBinder;
import agent.next.brain.session.TargetFrameSource;
import agent.next.brain.session.VerifierRegistry;
import agent.next.brain.session.VisualSubgoalVerifier;
import agent.next.device.DeviceException;
import agent.next.device.DisplayHandle;
import agent.next.device.Frame;
import agent.next.device.Observation;
import agent.next.device.NodeFlag;
import agent.next.device.Point;
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;
import android.view.Surface;
import agent.next.device.UiNode;
import agent.phone.capability.BlockingDeviceApi;
import agent.phone.connection.ShizukuConnectionDriver;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide task runtime: device, plan review, session, stop, and UI callbacks. */
public final class AgentRuntime {

    private static final String TAG = "AgentNextRuntime";
    private static volatile AgentRuntime instance;

    public enum State {
        IDLE,
        PLANNING,
        AWAITING_PLAN,
        RUNNING,
        STOPPING,
        DONE,
        NEED_USER,
        STOPPED,
        FAILED
    }

    public interface Listener {
        void onState(State state, String detail);

        void onPlan(PlanningResult result);

        void onSessionResult(SessionResult result);
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private final InMemoryWorkingMemory memory = new InMemoryWorkingMemory();
    private final EncryptedEpisodicMemory episodic;
    private final CalendarStore calendarStore;
    private final UiHumanGateway human = new UiHumanGateway();
    private volatile Listener listener;
    private volatile State state = State.IDLE;
    private volatile String detail = "";
    private BlockingDeviceApi api;
    private DisplayHandle display;
    private TaskPlan plan;
    private ChatCompletionClient client;
    private DeepSeekSkillModel skillModel;
    private DeepSeekVisualModel visualModel;
    private RuntimeControl control;
    private TargetHandle activeTarget;

    private AgentRuntime(Context context) {
        this.context = context.getApplicationContext();
        this.episodic = new EncryptedEpisodicMemory(this.context);
        this.calendarStore = new CalendarStore(this.context);
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

    public void plan(PlanningRequest request) {
        synchronized (this) {
            if (state == State.PLANNING || state == State.RUNNING || state == State.STOPPING) {
                postState(State.FAILED, "已有任务进行中");
                return;
            }
            state = State.PLANNING;
            detail = "规划中";
        }
        postState(State.PLANNING, "规划中");
        worker.execute(() -> doPlan(request));
    }

    public void runPlan() {
        TaskPlan current;
        synchronized (this) {
            if (state != State.AWAITING_PLAN || plan == null) {
                return;
            }
            current = plan;
            state = State.RUNNING;
            detail = "执行中";
            control = new RuntimeControl();
        }
        postState(State.RUNNING, "执行中");
        worker.execute(() -> doRun(current));
    }

    private volatile java.util.concurrent.CountDownLatch handoffLatch;
    private volatile boolean handoffApproved;

    /** Moves the target to the main screen and waits for the user to finish verification. */
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
            AgentTaskService.start(context);
            AgentTaskService.handoff(context, reason);
            currentApi.tasks().moveToMain(target);
            Log.i(TAG, "handoff: target moved to main");
            if (!latch.await(15, java.util.concurrent.TimeUnit.MINUTES)) {
                return false;
            }
            if (!handoffApproved) {
                Log.i(TAG, "handoff: not approved");
                return false;
            }
            currentApi.tasks().moveToPrivate(target, currentDisplay);
            currentApi.observations().observe(target, ObservationOptions.defaults());
            Log.i(TAG, "handoff: returned to private display");
            return true;
        } catch (Throwable failure) {
            Log.w(TAG, "handoff failed: " + failure);
            return false;
        } finally {
            handoffLatch = null;
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
        RuntimeControl current = control;
        if (current != null) {
            current.stop();
        }
        postState(State.STOPPING, "正在停止");
    }

    @FunctionalInterface
    public interface PreviewCallback {
        void done(boolean ok, String message);
    }

    public void pauseForUser() {
        RuntimeControl current = control;
        if (current != null) {
            current.pause("preview");
            detail = "用户接管，agent 已暂停";
            postState(state, detail);
        }
    }

    public void resumeAfterUser() {
        RuntimeControl current = control;
        if (current != null) {
            current.resume();
            detail = "agent 恢复执行";
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
                currentApi.startPreview(currentDisplay, surface);
                finish(callback, true, "preview mirror started");
            } catch (Throwable failure) {
                finish(callback, false, "preview start failed: " + failure.getMessage());
            }
        });
    }

    public void stopPreview() {
        BlockingDeviceApi currentApi = api;
        DisplayHandle currentDisplay = display;
        if (currentApi == null || currentDisplay == null) {
            return;
        }
        previewWorker.execute(() -> {
            try {
                currentApi.stopPreview(currentDisplay);
            } catch (Throwable ignored) {
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
        worker.execute(this::closeDevice);
        state = State.IDLE;
        detail = "";
        plan = null;
        control = null;
    }

    private void doPlan(PlanningRequest request) {
        try {
            ensureDevice();
            Optional<ModelConfig> config = EncryptedModelConfigStore.load(context);
            if (config.isEmpty()) {
                fail("缺少加密 model config（请导入 model-config.json 以迁移）");
                return;
            }
            client = new ChatCompletionClient(config.get(), new ModelCallBudget(80));
            skillModel = new DeepSeekSkillModel(client);
            visualModel = new DeepSeekVisualModel(client);
            DeepSeekTaskPlanner planner = new DeepSeekTaskPlanner(client);
            PlanningResult result = planner.plan(request);
            synchronized (this) {
                if (result.needsClarification()) {
                    state = State.AWAITING_PLAN;
                    detail = "需要澄清";
                } else {
                    plan = result.plan();
                    state = State.AWAITING_PLAN;
                    detail = "等待确认计划";
                }
            }
            postState(state, detail);
            Listener current = listener;
            if (current != null) {
                main.post(() -> current.onPlan(result));
            }
        } catch (Throwable failure) {
            Log.e(TAG, "planning failed", failure);
            fail("规划失败: " + failure.getMessage());
        }
    }

    private void doRun(TaskPlan confirmed) {
        RuntimeControl runControl = control;
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
                    try {
                        Thread.sleep(1200L);
                        api.observations().observe(opened, ObservationOptions.defaults());
                    } catch (Throwable waitFailure) {
                        Log.w(TAG, "target settle failed: " + waitFailure);
                    }
                    prepareAppEntry(packageName, opened, confirmed.task());
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
            SkillModel loggingModel = request -> {
                SkillModelStep step = skillModel.next(request);
                String extra = "";
                if (step instanceof SkillModelStep.SetText setText) {
                    extra = " text=" + setText.text() + " selector="
                            + setText.selector().description();
                } else if (step instanceof SkillModelStep.Tap tap) {
                    extra = " selector=" + tap.selector().description();
                }
                Log.i(TAG, "skill step=" + request.step() + " -> "
                        + (step == null ? "null" : step.getClass().getSimpleName()) + extra);
                return step;
            };
            SessionPolicyFactory promptPolicies = new PromptSessionPolicyFactory(
                    loggingModel, frames, memory);
            SessionPolicyFactory policies = (task, subgoal, target, attempt) -> {
                if ("com.android.calendar".equals(target.packageName())) {
                    return new RiskGuardPolicy(new CalendarPolicy(calendarStore, task, subgoal), this::handoffToUser);
                }
                if ("com.quark.browser".equals(target.packageName())
                        && task.goal().contains("天气")) {
                    return new RiskGuardPolicy(new QuarkWeatherPolicy(task, subgoal), this::handoffToUser);
                }
                if ("com.jingdong.app.mall".equals(target.packageName())
                        && (subgoal.intent().contains("输入")
                        || subgoal.intent().contains("提交")
                        || subgoal.intent().contains("搜索"))) {
                    prepareJdSearchEntry(target);
                }
                if ("com.jingdong.app.mall".equals(target.packageName())
                        && subgoal.intent().contains("加入购物车")) {
                    Log.i(TAG, "JD add-to-cart policy selected: " + subgoal.id());
                    return new RiskGuardPolicy(new JdAddPolicy(), this::handoffToUser);
                }
                if ("com.jingdong.app.mall".equals(target.packageName())
                        && ("jd.cart".equals(subgoal.verifierId())
                        || subgoal.intent().contains("购物车"))) {
                    Log.i(TAG, "JD cart policy selected: " + subgoal.id()
                            + " verifier=" + subgoal.verifierId());
                    prepareJdCart(target);
                    return new RiskGuardPolicy(new JdCartPolicy(), this::handoffToUser);
                }
                return new RiskGuardPolicy(promptPolicies.create(task, subgoal, target, attempt), this::handoffToUser);
            };
            VerifierRegistry verifiers = new VerifierRegistry();
            SubgoalVerifier visualDelegate = new VisualSubgoalVerifier(visualModel, frames);
            SubgoalVerifier visual = (task, subgoal, target, observation) -> {
                SubgoalVerifier.Verification verification =
                        visualDelegate.verify(task, subgoal, target, observation);
                StringBuilder sample = new StringBuilder();
                for (var node : observation.nodes()) {
                    if (sample.length() > 200) {
                        break;
                    }
                    if (!node.text().isBlank()) {
                        sample.append(node.text()).append(" | ");
                    } else if (!node.viewId().isBlank()) {
                        sample.append("id=").append(node.viewId()).append(" | ");
                    } else if (!node.contentDescription().isBlank()) {
                        sample.append("desc=").append(node.contentDescription()).append(" | ");
                    }
                }
                Log.i(TAG, "verify " + subgoal.id() + " -> " + verification.satisfied()
                        + " (" + verification.evidence() + ") nodes="
                        + observation.nodes().size() + " pkg=" + observation.packageName()
                        + " act=" + observation.activityName() + " text=" + sample);
                return verification;
            };
            CalendarSubgoalVerifier calendarVerifier =
                    new CalendarSubgoalVerifier(calendarStore);
            SubgoalVerifier routed = (task, subgoal, target, observation) -> {
                if ("com.jingdong.app.mall".equals(target.packageName())
                        && task.goal().contains("购物车")) {
                    SubgoalVerifier.Verification cart = new JdCartVerifier().verify(
                            task, subgoal, target, observation);
                    if (cart.satisfied()) {
                        Log.i(TAG, "verify cart-goal " + subgoal.id() + " -> true");
                        return cart;
                    }
                }
                if ("com.android.calendar".equals(target.packageName())) {
                    SubgoalVerifier.Verification verification = calendarVerifier.verify(
                            task, subgoal, target, observation);
                    Log.i(TAG, "verify calendar " + subgoal.id() + " -> "
                            + verification.satisfied() + " (" + verification.evidence() + ")");
                    return verification;
                }
                return visual.verify(task, subgoal, target, observation);
            };
            verifiers.register("visual", routed);
            verifiers.register("calendar.event", routed);
            verifiers.register("jd.cart", routed);
            SessionRunner runner = new SessionRunner(api, confirmed, binder, policies,
                    slow -> new SlowLoopDecision.Stop("v1 stops on slow loop"),
                    verifiers, new DefaultRiskGate(), human, runControl, memory, episodic,
                    ObservationOptions.defaults(), 20, 6,
                    (task, subgoal, target, observation) -> extractStage(task, target, observation));
            SessionResult result = runner.run();
            synchronized (this) {
                state = switch (result.status()) {
                    case DONE -> State.DONE;
                    case NEED_USER -> State.NEED_USER;
                    case STOPPED -> State.STOPPED;
                    default -> State.FAILED;
                };
                detail = result.status().name() + " · " + result.detail();
            }
            postState(state, detail);
            Listener current = listener;
            if (current != null) {
                main.post(() -> current.onSessionResult(result));
            }
        } catch (Throwable failure) {
            Log.e(TAG, "session failed", failure);
            fail("执行失败: " + failure.getMessage());
        } finally {
            closeDevice();
        }
    }

    private void prepareAppEntry(String packageName, TargetHandle target,
            agent.next.brain.TaskSpec task) {
        if ("com.jingdong.app.mall".equals(packageName)) {
            prepareJdHome(target);
            if (task.goal().contains("购物车") && !task.goal().contains("加入购物车")) {
                prepareJdCart(target);
            }
            return;
        }
        if (!"com.quark.browser".equals(packageName)) {
            return;
        }
        try {
            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                    return;
                }
            }
            UiNode entry = null;
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.CLICKABLE)
                        && (node.text().contains("把问题和任务告诉我")
                        || node.text().contains("搜索"))) {
                    entry = node;
                    break;
                }
            }
            if (entry == null) {
                Log.i(TAG, "Quark entry not found");
                return;
            }
            Point point = new Point(entry.bounds().centerX(), entry.bounds().centerY());
            api.input().tap(target, observation, point);
            Thread.sleep(800L);
            api.observations().observe(target, ObservationOptions.defaults());
            Log.i(TAG, "Quark search entry opened");
        } catch (Throwable failure) {
            Log.w(TAG, "Quark entry prelude failed: " + failure);
        }
    }

    private void prepareJdHome(TargetHandle target) {
        try {
            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            // JD may restore a Babel/11.11 event page with no bottom navigation.
            for (int attempt = 0; attempt < 3; attempt++) {
                boolean babel = observation.activityName().contains("BabelActivity");
                StringBuilder texts = new StringBuilder();
                for (UiNode node : observation.nodes()) {
                    texts.append(node.text()).append(' ').append(node.contentDescription());
                }
                if (!babel && !texts.toString().contains("11.11主会场")
                        && !texts.toString().contains("J_babelOptPage")) {
                    break;
                }
                Log.i(TAG, "JD event page detected, back");
                api.input().back(target, observation);
                Thread.sleep(900L);
                observation = api.observations().observe(
                        target, ObservationOptions.defaults());
            }
            // JD restores its last activity (cart/detail) on reopen; the bottom Home
            // tab is a custom view without text, so use the scoped 720x1280 hit area.
            api.input().tap(target, observation, new Point(72, 1210));
            Thread.sleep(800L);
            observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            Log.i(TAG, "JD home tab selected act=" + observation.activityName()
                    + " nodes=" + observation.nodes().size());
        } catch (Throwable failure) {
            Log.w(TAG, "JD home prelude failed: " + failure);
        }
    }

    private void prepareJdSearchEntry(TargetHandle target) {
        try {
            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                    return;
                }
            }
            UiNode entry = null;
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.CLICKABLE) && (node.text().contains("搜索")
                        || node.contentDescription().contains("搜索"))) {
                    entry = node;
                    break;
                }
            }
            if (entry == null) {
                for (UiNode node : observation.nodes()) {
                    if (node.hasFlag(NodeFlag.CLICKABLE) && node.bounds().top() < 400
                            && node.bounds().width() > 300 && node.bounds().height() < 200) {
                        entry = node;
                        break;
                    }
                }
            }
            if (entry == null) {
                Log.i(TAG, "JD search entry not in tree; using scoped top search coordinate");
                api.input().tap(target, observation, new Point(360, 180));
                Thread.sleep(700L);
                api.observations().observe(target, ObservationOptions.defaults());
                return;
            }
            Point point = new Point(entry.bounds().centerX(), entry.bounds().centerY());
            api.input().tap(target, observation, point);
            Thread.sleep(700L);
            api.observations().observe(target, ObservationOptions.defaults());
            Log.i(TAG, "JD search entry opened");
        } catch (Throwable failure) {
            Log.w(TAG, "JD search entry prelude failed: " + failure);
        }
    }

    private void prepareJdCart(TargetHandle target) {
        try {
            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            for (UiNode node : observation.nodes()) {
                String popup = node.text() + " " + node.contentDescription();
                if (popup.contains("别折腾了") || popup.contains("跳过广告")) {
                    api.input().back(target, observation);
                    Thread.sleep(1000L);
                    observation = api.observations().observe(
                            target, ObservationOptions.defaults());
                    break;
                }
            }
            boolean title = false;
            boolean footer = false;
            for (UiNode node : observation.nodes()) {
                if (node.viewId().contains("cart.feature")) {
                    Log.i(TAG, "JD cart view already visible");
                    return;
                }
                String text = node.text() + " " + node.contentDescription();
                if (text.contains("购物车")) {
                    title = true;
                }
                if (text.contains("合计") || text.contains("去结算")) {
                    footer = true;
                }
            }
            if (title && footer) {
                return;
            }
            // JD's bottom cart tab is a custom view without text; scoped 720x1280 hit area.
            api.input().tap(target, observation, new Point(486, 1210));
            Thread.sleep(1500L);
            Observation cart = api.observations().observe(
                    target, ObservationOptions.defaults());
            StringBuilder cartSample = new StringBuilder();
            for (UiNode node : cart.nodes()) {
                String value = node.text().isBlank()
                        ? node.contentDescription() : node.text();
                if (!value.isBlank() && cartSample.length() < 220) {
                    cartSample.append(value).append(" | ");
                }
            }
            Log.i(TAG, "JD cart after tap act=" + cart.activityName()
                    + " nodes=" + cart.nodes().size() + " text=" + cartSample);
        } catch (Throwable failure) {
            Log.w(TAG, "JD cart prelude failed: " + failure);
        }
    }

    private agent.next.brain.model.StageData extractStage(TaskSpec task, TargetHandle target,
            Observation observation) throws DeviceException {
        Frame frame = api.observations().captureFrame(target);
        List<String> text = new ArrayList<>();
        for (UiNode node : observation.nodes()) {
            String value = node.text() == null ? "" : node.text().trim();
            if (!value.isEmpty() && text.size() < 80) {
                text.add(value.length() > 200 ? value.substring(0, 200) : value);
            }
        }
        try {
            return visualModel.extract(new StageExtractRequest(task.goal(), frame, text));
        } catch (java.io.IOException failure) {
            throw new DeviceException(DeviceException.Code.INTERNAL,
                    "stage extraction failed", failure);
        }
    }

    private void ensureDevice() throws DeviceException {
        if (api == null) {
            api = new BlockingDeviceApi(
                    listener -> new ShizukuConnectionDriver(context, listener), 60_000L);
            api.connect();
        }
        if (display == null) {
            display = api.displays().create(agent.next.device.DisplaySpec.standard());
        }
    }

    private void closeDevice() {
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
    }

    private void fail(String message) {
        synchronized (this) {
            state = State.FAILED;
            detail = message;
        }
        postState(State.FAILED, message);
        closeDevice();
    }

    private void postState(State next, String message) {
        Listener current = listener;
        if (current != null) {
            main.post(() -> current.onState(next, message));
        }
    }

    private static final class RuntimeControl implements LoopControl {
        private boolean stopped;
        private boolean paused;

        @Override
        public synchronized void checkpoint() throws LoopStoppedException {
            while (paused && !stopped) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new LoopStoppedException("agent interrupted");
                }
            }
            if (stopped) {
                throw new LoopStoppedException("user stopped the task");
            }
        }

        synchronized void pause(String reason) {
            paused = true;
        }

        synchronized void resume() {
            paused = false;
            notifyAll();
        }

        synchronized void stop() {
            stopped = true;
            notifyAll();
        }
    }
}
