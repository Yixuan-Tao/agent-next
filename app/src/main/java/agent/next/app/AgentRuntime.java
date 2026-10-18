package agent.next.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import agent.next.brain.DefaultRiskGate;
import agent.next.brain.InMemoryEpisodicMemory;
import agent.next.brain.InMemoryWorkingMemory;
import agent.next.brain.loop.LoopStoppedException;
import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopControl;
import agent.next.brain.loop.SlowLoopDecision;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.DeepSeekSkillModel;
import agent.next.brain.model.DeepSeekVisualModel;
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
import agent.next.device.ObservationOptions;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;
import agent.phone.capability.BlockingDeviceApi;
import agent.phone.connection.ShizukuConnectionDriver;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final InMemoryWorkingMemory memory = new InMemoryWorkingMemory();
    private final InMemoryEpisodicMemory episodic = new InMemoryEpisodicMemory();
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

    public void stop() {
        RuntimeControl current = control;
        if (current != null) {
            current.stop();
        }
        postState(State.STOPPING, "正在停止");
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
            Optional<ModelConfig> config = ModelConfig.load(
                    new File(context.getFilesDir(), "model-config.json").toPath());
            if (config.isEmpty()) {
                fail("缺少 files/model-config.json");
                return;
            }
            client = new ChatCompletionClient(config.get());
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
                Log.i(TAG, "skill step=" + request.step() + " -> "
                        + (step == null ? "null" : step.getClass().getSimpleName()));
                return step;
            };
            SessionPolicyFactory policies = new PromptSessionPolicyFactory(
                    loggingModel, frames, memory);
            VerifierRegistry verifiers = new VerifierRegistry();
            SubgoalVerifier visual = (task, subgoal, target, observation) -> {
                SubgoalVerifier.Verification verification =
                        new VisualSubgoalVerifier(visualModel, frames)
                                .verify(task, subgoal, target, observation);
                Log.i(TAG, "verify " + subgoal.id() + " -> " + verification.satisfied()
                        + " (" + verification.evidence() + ")");
                return verification;
            };
            verifiers.register("visual", visual);
            verifiers.register("calendar.event", visual);
            verifiers.register("jd.cart", visual);
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
        private final AtomicBoolean stopped = new AtomicBoolean();

        @Override
        public void checkpoint() throws LoopStoppedException {
            if (stopped.get()) {
                throw new LoopStoppedException("user stopped the task");
            }
        }

        void stop() {
            stopped.set(true);
        }
    }
}
