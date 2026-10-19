package agent.next.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
import agent.next.brain.loop.LoopAction;
import agent.next.brain.loop.LoopContext;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.loop.LoopPolicyFactory;
import agent.next.brain.loop.LoopRunResult;
import agent.next.brain.loop.LoopRunner;
import agent.next.brain.loop.LoopResult;
import agent.next.brain.loop.NodeSelector;
import agent.next.brain.loop.SetTextAction;
import agent.next.brain.loop.SlowLoopDecision;
import agent.next.brain.loop.SlowLoopPolicy;
import agent.next.brain.loop.TapAction;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.DeepSeekSkillModel;
import agent.next.brain.model.DeepSeekVisualModel;
import agent.next.brain.model.ModelConfig;
import agent.next.brain.model.StageData;
import agent.next.brain.model.StageExtractRequest;
import agent.next.brain.model.VisualVerification;
import agent.next.brain.model.VisualVerificationRequest;
import agent.next.brain.skill.PromptSkillPolicy;
import agent.next.brain.skill.SkillApplicability;
import agent.next.brain.skill.SkillBody;
import agent.next.brain.skill.SkillCapability;
import agent.next.brain.skill.SkillFrameSource;
import agent.next.brain.skill.SkillModel;
import agent.next.brain.skill.SkillModelRequest;
import agent.next.brain.skill.SkillModelStep;
import agent.next.brain.skill.SkillPackage;
import agent.next.brain.skill.SkillPermissions;
import agent.next.brain.skill.SkillProvenance;
import agent.next.brain.skill.SkillTrust;
import agent.next.brain.skill.SkillVerifier;
import agent.next.device.DeviceApi;
import agent.next.device.DeviceCapabilities;
import agent.next.device.DeviceException;
import agent.next.device.DeviceFeature;
import agent.next.device.DisplayHandle;
import agent.next.device.DisplaySpec;
import agent.next.device.Frame;
import agent.next.device.InputReceipt;
import agent.next.device.InputStatus;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.ObservationStatus;
import agent.next.device.Point;
import agent.next.device.TargetHandle;
import agent.next.device.TargetInfo;
import agent.next.device.TargetSpec;
import agent.next.device.UiNode;
import agent.phone.capability.BlockingDeviceApi;
import agent.phone.capability.PreviewCapabilities;
import agent.phone.connection.ShizukuConnectionDriver;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal app shell used to verify the synchronous device-api adapter on a
 * real device. It is intentionally not the product UI.
 */
public final class ShellActivity extends Activity {

    private static final String TAG = "AgentNextSmoke";
    private static final String TARGET_PACKAGE = "com.jingdong.app.mall";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    private TextView log;
    private ScrollView scroll;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        if ("loop".equals(getIntent().getStringExtra("mode"))) {
            startFastLoop();
        } else if ("runner".equals(getIntent().getStringExtra("mode"))) {
            startLoopRunner();
        } else if ("prompt".equals(getIntent().getStringExtra("mode"))) {
            startPromptSkill();
        } else if ("compat".equals(getIntent().getStringExtra("mode"))) {
            startCompatProbe();
        } else if (getIntent().getBooleanExtra("autorun", true)) {
            startSmoke();
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout buildUi() {
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("AgentNext Shell · device-api smoke");
        title.setTextSize(20);
        title.setTextColor(Color.rgb(20, 20, 20));
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Smoke: device-api steps. Fast loop: FastLoop drives a JD search. LoopRunner: first attempt fails, slow loop replans, second attempt succeeds.");
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(80, 80, 80));
        hint.setPadding(0, padding / 2, 0, padding / 2);
        root.addView(hint);

        log = new TextView(this);
        log.setTextSize(12);
        log.setTypeface(Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setTextColor(Color.rgb(30, 30, 30));
        scroll = new ScrollView(this);
        scroll.addView(log, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button run = new Button(this);
        run.setText("Run smoke again");
        run.setOnClickListener(view -> startSmoke());
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = padding;
        root.addView(run, buttonParams);

        Button fastLoop = new Button(this);
        fastLoop.setText("Run fast loop (JD search)");
        fastLoop.setOnClickListener(view -> startFastLoop());
        LinearLayout.LayoutParams fastLoopParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fastLoopParams.topMargin = padding / 2;
        root.addView(fastLoop, fastLoopParams);

        Button runner = new Button(this);
        runner.setText("Run LoopRunner (replan)");
        runner.setOnClickListener(view -> startLoopRunner());
        LinearLayout.LayoutParams runnerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        runnerParams.topMargin = padding / 2;
        root.addView(runner, runnerParams);

        Button prompt = new Button(this);
        prompt.setText("Run prompt model (JD search)");
        prompt.setOnClickListener(view -> startPromptSkill());
        LinearLayout.LayoutParams promptParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptParams.topMargin = padding / 2;
        root.addView(prompt, promptParams);

        Button compat = new Button(this);
        compat.setText("Run compatibility probe");
        compat.setOnClickListener(view -> startCompatProbe());
        LinearLayout.LayoutParams compatParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        compatParams.topMargin = padding / 2;
        root.addView(compat, compatParams);

        Button preview = new Button(this);
        preview.setText("Open preview / touch takeover");
        preview.setOnClickListener(view ->
                startActivity(new Intent(this, PreviewActivity.class)));
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        previewParams.topMargin = padding / 2;
        root.addView(preview, previewParams);
        root.setGravity(Gravity.TOP);
        return root;
    }

    private void startSmoke() {
        if (running) {
            return;
        }
        running = true;
        append("--- smoke start ---");
        worker.execute(this::runSmoke);
    }

    private void startFastLoop() {
        if (running) {
            return;
        }
        running = true;
        append("--- fast loop start ---");
        worker.execute(this::runFastLoop);
    }

    private void startLoopRunner() {
        if (running) {
            return;
        }
        running = true;
        append("--- loop runner start ---");
        worker.execute(this::runLoopRunner);
    }

    private void startPromptSkill() {
        if (running) {
            return;
        }
        running = true;
        append("--- prompt skill start ---");
        worker.execute(this::runPromptSkill);
    }

    private void startCompatProbe() {
        if (running) {
            return;
        }
        running = true;
        append("--- compatibility probe start ---");
        worker.execute(this::runCompatProbe);
    }

    private void runCompatProbe() {
        BlockingDeviceApi api = null;
        DisplayHandle display = null;
        TargetHandle target = null;
        try {
            append("compat: android=" + Build.VERSION.RELEASE
                    + " sdk=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL);
            append("compat: " + PreviewCapabilities.probe());
            String packageName = getIntent().getStringExtra("package");
            if (packageName == null || packageName.isBlank()) {
                packageName = TARGET_PACKAGE;
            }
            if (!isInstalled(packageName)) {
                append("COMPAT SKIP: package not installed " + packageName);
                return;
            }
            api = new BlockingDeviceApi(
                    listener -> new ShizukuConnectionDriver(getApplicationContext(), listener),
                    60_000L);
            append("compat: connect");
            api.connect();
            DeviceCapabilities capabilities = api.capabilities();
            append("compat: device=" + capabilities
                    + " previewMirror=" + capabilities.has(DeviceFeature.PREVIEW_MIRROR));
            display = api.displays().create(DisplaySpec.standard());
            append("compat: display " + display.token());
            target = api.targets().open(display,
                    TargetSpec.single("compat-task", packageName));
            append("compat: target " + target.token());
            Observation observation = observeReady(api, target, "compat");
            append("compat: observe nodes=" + observation.nodes().size()
                    + " package=" + observation.packageName());
            Frame frame = api.observations().captureFrame(target);
            append("compat: frame " + frame.width() + "x" + frame.height()
                    + " png=" + frame.png().length);
            append("COMPAT PASS");
        } catch (Throwable failure) {
            Log.e(TAG, "compatibility probe failed", failure);
            append("COMPAT FAIL: " + failure);
        } finally {
            if (target != null) {
                try {
                    api.targets().release(target);
                    append("compat: target released");
                } catch (Throwable failure) {
                    append("compat: release target " + failure);
                }
            }
            if (display != null) {
                try {
                    api.displays().release(display);
                    append("compat: display released");
                } catch (Throwable failure) {
                    append("compat: release display " + failure);
                }
            }
            if (api != null) {
                api.close();
            }
            running = false;
            append("--- compatibility probe end ---");
        }
    }

    private boolean isInstalled(String packageName) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    private void runPromptSkill() {
        BlockingDeviceApi api = null;
        DisplayHandle display = null;
        TargetHandle target = null;
        try {
            File configFile = new File(getFilesDir(), "model-config.json");
            Optional<ModelConfig> config = ModelConfig.load(configFile.toPath());
            if (!config.isPresent()) {
                append("prompt: missing " + configFile + " (copy model-config.json into app files)");
                return;
            }
            append("prompt: model=" + config.get().model()
                    + " base=" + config.get().baseUrl());
            ChatCompletionClient client = new ChatCompletionClient(config.get());
            final DeepSeekSkillModel deepSeek = new DeepSeekSkillModel(client);
            SkillModel model = new SkillModel() {
                @Override
                public SkillModelStep next(SkillModelRequest request) {
                    SkillModelStep step = deepSeek.next(request);
                    append("prompt model step=" + request.step() + " -> " + describe(step));
                    return step;
                }
            };

            api = new BlockingDeviceApi(
                    listener -> new ShizukuConnectionDriver(getApplicationContext(), listener),
                    60_000L);
            append("prompt: connect");
            api.connect();
            display = api.displays().create(DisplaySpec.standard());
            append("prompt: display " + display.token());
            target = api.targets().open(display,
                    TargetSpec.single("fast-loop-task", TARGET_PACKAGE));
            append("prompt: target " + target.token());

            Observation home = observeReady(api, target, "prompt-home");
            UiNode entry = findSearchEntry(home);
            Point point = entry == null ? new Point(360, 180) : center(entry);
            append("prompt: open search entry=" + (entry == null ? "fixed" : entry.index()));
            api.input().tap(target, home, point);
            Thread.sleep(700L);
            Observation search = observeReady(api, target, "prompt-search");
            append("prompt: search page nodes=" + search.nodes().size()
                    + " editable=" + (findEditable(search) != null));

            final BlockingDeviceApi deviceApi = api;
            final TargetHandle boundTarget = target;
            SkillFrameSource frames = new SkillFrameSource() {
                @Override
                public Frame capture() throws DeviceException {
                    return deviceApi.observations().captureFrame(boundTarget);
                }
            };
            PromptSkillPolicy policy = new PromptSkillPolicy(
                    promptSearchSkill(), Collections.singletonMap("query", "短袖T恤 男款 XL"),
                    model, frames, 8);
            FastLoop loop = new FastLoop(api, target, loopTask(3), loopGraph(), policy,
                    new DefaultRiskGate(), new UnavailableHumanGateway(),
                    new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(),
                    ObservationOptions.defaults(), 20);
            LoopResult result = loop.run();
            append("prompt result: " + result.status()
                    + " actions=" + result.actions()
                    + " detail=" + result.detail());
            Frame finalFrame = null;
            if (result.lastObservation() != null) {
                append("prompt last: " + summarize(result.lastObservation()));
                try {
                    finalFrame = api.observations().captureFrame(target);
                    saveFrame(finalFrame);
                } catch (Throwable failure) {
                    append("prompt frame: " + failure);
                }
            }
            boolean visualOk = false;
            boolean visualExtractOk = false;
            DeepSeekVisualModel visual = null;
            if (result.status() == LoopResult.Status.DONE && finalFrame != null) {
                try {
                    visual = new DeepSeekVisualModel(client);
                    VisualVerification verification = visual.verify(
                            new VisualVerificationRequest(
                                    "在京东搜索页搜索 短袖T恤 男款 XL 并看到结果页",
                                    List.of("京东搜索结果页", "搜索词 短袖T恤 男款 XL"),
                                    finalFrame));
                    visualOk = verification.verified();
                    append("prompt visual verify: verified=" + verification.verified()
                            + " matched=" + verification.matched().size()
                            + " missing=" + verification.missing().size());
                } catch (Throwable failure) {
                    append("prompt visual verify failed: " + failure);
                }
                if (visual != null && result.lastObservation() != null) {
                    try {
                        StageData stage = visual.extract(new StageExtractRequest(
                                "京东搜索 短袖T恤 男款 XL 的结果页", finalFrame,
                                visibleTexts(result.lastObservation())));
                        visualExtractOk = true;
                        append("prompt visual extract: summaryChars="
                                + stage.summary().length() + " entities="
                                + stage.entities().size());
                    } catch (Throwable failure) {
                        append("prompt visual extract failed: " + failure);
                    }
                }
            } else if (result.status() == LoopResult.Status.DONE) {
                append("prompt visual verify: no final frame");
            }
            if (result.status() == LoopResult.Status.DONE && visualOk && visualExtractOk) {
                append("PROMPT SKILL PASS");
            } else if (result.status() == LoopResult.Status.DONE && visualOk) {
                append("PROMPT SKILL EXTRACT FAIL");
            } else if (result.status() == LoopResult.Status.DONE) {
                append("PROMPT SKILL VERIFY FAIL");
            } else {
                append("PROMPT SKILL STOP: " + result.status());
            }
        } catch (Throwable failure) {
            Log.e(TAG, "prompt skill failed", failure);
            append("PROMPT SKILL FAIL: " + failure);
        } finally {
            if (target != null) {
                try {
                    api.targets().release(target);
                    append("prompt target released");
                } catch (Throwable failure) {
                    append("prompt release target: " + failure);
                }
            }
            if (display != null) {
                try {
                    api.displays().release(display);
                    append("prompt display released");
                } catch (Throwable failure) {
                    append("prompt release display: " + failure);
                }
            }
            if (api != null) {
                api.close();
            }
            running = false;
            append("--- prompt skill end ---");
        }
    }

    private static SkillPackage promptSearchSkill() {
        SkillBody body = new SkillBody.Prompt(
                "当前是京东搜索输入页。先用 SetText 在 editable 节点输入 ${query}，"
                        + "再 Tap clickable text '搜索'。点击后若结果页还没出现筛选项，输出 Wait 700；"
                        + "看到 全部/新品/今日上新/奥莱/明日达 或 综合推荐/销量 后输出 Done。"
                        + "不要点击商品、购物车、结算或支付。");
        return new SkillPackage(
                "jd.search.prompt", "1.0.0", "search JD from the search page",
                body,
                new SkillPermissions(Set.of(SkillCapability.TAP, SkillCapability.SET_TEXT),
                        Set.of(TARGET_PACKAGE)),
                (observation, targetPackage, parameters) ->
                        new SkillApplicability.Result(TARGET_PACKAGE.equals(targetPackage),
                                "JD target"),
                (observation, parameters) -> {
                    for (UiNode node : observation.nodes()) {
                        String text = node.text();
                        if (text.contains("全部") || text.contains("新品")
                                || text.contains("今日上新") || text.contains("奥莱")
                                || text.contains("明日达") || text.contains("综合推荐")
                                || text.contains("销量")) {
                            return new SkillVerifier.Result(true, "JD results visible");
                        }
                    }
                    return new SkillVerifier.Result(false, "JD results not visible");
                },
                SkillTrust.VERIFIED,
                new SkillProvenance("app", "agent-next-app", "local-prompt", Instant.now()));
    }

    private static String describe(SkillModelStep step) {
        if (step instanceof SkillModelStep.Tap) {
            SkillModelStep.Tap tap = (SkillModelStep.Tap) step;
            return "Tap " + tap.selector().description() + " risk=" + tap.risk();
        }
        if (step instanceof SkillModelStep.SetText) {
            SkillModelStep.SetText setText = (SkillModelStep.SetText) step;
            return "SetText " + setText.selector().description()
                    + " text=" + setText.text() + " risk=" + setText.risk();
        }
        if (step instanceof SkillModelStep.Back) {
            return "Back risk=" + ((SkillModelStep.Back) step).risk();
        }
        if (step instanceof SkillModelStep.Wait) {
            return "Wait " + ((SkillModelStep.Wait) step).millis() + "ms";
        }
        return "Done";
    }

    private void runFastLoop() {
        BlockingDeviceApi api = null;
        DisplayHandle display = null;
        TargetHandle target = null;
        try {
            api = new BlockingDeviceApi(
                    listener -> new ShizukuConnectionDriver(getApplicationContext(), listener),
                    60_000L);
            append("fast-loop: connect");
            api.connect();
            display = api.displays().create(DisplaySpec.standard());
            append("fast-loop: display " + display.token());
            target = api.targets().open(display,
                    TargetSpec.single("fast-loop-task", TARGET_PACKAGE));
            append("fast-loop: target " + target.token());

            TaskSpec task = loopTask(10);
            TaskGraph graph = loopGraph();
            FastLoop loop = new FastLoop(api, target, task, graph, new JdSearchPolicy(),
                    new DefaultRiskGate(), new UnavailableHumanGateway(),
                    new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(),
                    ObservationOptions.defaults(), 20);
            LoopResult result = loop.run();
            append("fast-loop result: " + result.status()
                    + " actions=" + result.actions()
                    + " detail=" + result.detail());
            if (result.lastObservation() != null) {
                append("fast-loop last: " + summarize(result.lastObservation()));
                try {
                    saveFrame(api.observations().captureFrame(target));
                } catch (Throwable failure) {
                    append("fast-loop frame: " + failure);
                }
            }
            if (result.status() == LoopResult.Status.DONE) {
                append("FAST LOOP PASS");
            } else {
                append("FAST LOOP STOP: " + result.status());
            }
        } catch (Throwable failure) {
            Log.e(TAG, "fast loop failed", failure);
            append("FAST LOOP FAIL: " + failure);
        } finally {
            if (target != null) {
                try {
                    api.targets().release(target);
                    append("fast-loop target released");
                } catch (Throwable failure) {
                    append("fast-loop release target: " + failure);
                }
            }
            if (display != null) {
                try {
                    api.displays().release(display);
                    append("fast-loop display released");
                } catch (Throwable failure) {
                    append("fast-loop release display: " + failure);
                }
            }
            if (api != null) {
                api.close();
            }
            running = false;
            append("--- fast loop end ---");
        }
    }

    private void runLoopRunner() {
        BlockingDeviceApi api = null;
        DisplayHandle display = null;
        TargetHandle target = null;
        try {
            api = new BlockingDeviceApi(
                    listener -> new ShizukuConnectionDriver(getApplicationContext(), listener),
                    60_000L);
            append("runner: connect");
            api.connect();
            display = api.displays().create(DisplaySpec.standard());
            append("runner: display " + display.token());
            target = api.targets().open(display,
                    TargetSpec.single("fast-loop-task", TARGET_PACKAGE));
            append("runner: target " + target.token());

            TaskSpec task = loopTask(2);
            TaskGraph graph = loopGraph();
            LoopPolicyFactory policies = (attempt, currentGraph) -> {
                String current = currentGraph.current().map(Subgoal::id).orElse("-");
                append("runner: attempt " + attempt + " policy="
                        + (attempt == 1 ? "no-op" : "JD search") + " current=" + current);
                return attempt == 1 ? new NoOpPolicy() : new JdSearchPolicy();
            };
            SlowLoopPolicy slow = context -> {
                append("runner: slow reason=" + context.fastResult().reason().orElse(null)
                        + " actions=" + context.fastResult().actions());
                return new SlowLoopDecision.Replace(new Subgoal(
                        "search", "search after replan", Set.of(), List.of(), List.of(),
                        new Fallback(Fallback.Strategy.RETRY, "retry after no progress"),
                        RiskClass.LOW, true), "first attempt made no progress");
            };
            LoopRunner runner = new LoopRunner(api, target, task, graph, policies, slow,
                    new DefaultRiskGate(), new UnavailableHumanGateway(),
                    new InMemoryWorkingMemory(), new InMemoryEpisodicMemory(),
                    ObservationOptions.defaults(), 20, 3);
            LoopRunResult run = runner.run();
            append("runner result: " + run.result().status()
                    + " attempts=" + run.attempts()
                    + " replans=" + run.replans()
                    + " totalActions=" + run.totalActions()
                    + " detail=" + run.result().detail());
            if (run.result().lastObservation() != null) {
                append("runner last: " + summarize(run.result().lastObservation()));
                try {
                    saveFrame(api.observations().captureFrame(target));
                } catch (Throwable failure) {
                    append("runner frame: " + failure);
                }
            }
            if (run.result().status() == LoopResult.Status.DONE) {
                append("LOOP RUNNER PASS");
            } else {
                append("LOOP RUNNER STOP: " + run.result().status());
            }
        } catch (Throwable failure) {
            Log.e(TAG, "loop runner failed", failure);
            append("LOOP RUNNER FAIL: " + failure);
        } finally {
            if (target != null) {
                try {
                    api.targets().release(target);
                    append("runner target released");
                } catch (Throwable failure) {
                    append("runner release target: " + failure);
                }
            }
            if (display != null) {
                try {
                    api.displays().release(display);
                    append("runner display released");
                } catch (Throwable failure) {
                    append("runner release display: " + failure);
                }
            }
            if (api != null) {
                api.close();
            }
            running = false;
            append("--- loop runner end ---");
        }
    }

    private static TaskSpec loopTask(int maxConsecutiveNoEffect) {
        return new TaskSpec(
                "fast-loop-task",
                "search JD for a shirt",
                List.of(),
                List.of(new SuccessCriterion("results", "results visible", true)),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT),
                Set.of(TARGET_PACKAGE),
                Set.of(),
                new Budget(40, Duration.ofMinutes(2), maxConsecutiveNoEffect, 10));
    }

    private static TaskGraph loopGraph() {
        return TaskGraph.of(new Subgoal(
                "search", "search and see results", Set.of(), List.of(), List.of(),
                new Fallback(Fallback.Strategy.ASK_USER, "ask the user"),
                RiskClass.LOW, true));
    }

    private void runSmoke() {
        BlockingDeviceApi api = null;
        DisplayHandle display = null;
        TargetHandle target = null;
        try {
            append("construct adapter");
            api = new BlockingDeviceApi(
                    listener -> new ShizukuConnectionDriver(
                            getApplicationContext(),
                            state -> {
                                append("state: " + state.status + "/" + state.reason);
                                listener.onStateChanged(state);
                            }),
                    60_000L);
            append("connect (approve Shizuku permission if prompted)");
            api.connect();
            DeviceCapabilities capabilities = api.capabilities();
            append("connected: protocol=" + capabilities.protocolVersion()
                    + " api=" + capabilities.apiLevel()
                    + " features=" + capabilities.features().size());

            display = api.displays().create(DisplaySpec.standard());
            append("display: " + display.token() + " generation=" + display.generation());

            target = api.targets().open(display,
                    TargetSpec.single("smoke-task", TARGET_PACKAGE));
            append("target: " + target.token() + " package=" + target.packageName());

            Observation home = observeReady(api, target, "home");
            int editable = 0;
            int clickable = 0;
            for (UiNode node : home.nodes()) {
                if (node.hasFlag(NodeFlag.EDITABLE)) {
                    editable++;
                }
                if (node.hasFlag(NodeFlag.CLICKABLE)) {
                    clickable++;
                }
            }
            append("editable=" + editable + " clickable=" + clickable);

            Frame frame = api.observations().captureFrame(target);
            append("frame: " + frame.width() + "x" + frame.height()
                    + " png=" + frame.png().length + " sequence=" + frame.sequence());
            saveFrame(frame);

            UiNode searchEntry = findSearchEntry(home);
            Point searchPoint;
            if (searchEntry == null) {
                // JD 16.1 renders the home search bar without accessibility text
                // or a clickable node; this shell is a fixed smoke probe.
                searchPoint = new Point(360, 180);
                append("search entry not in tree; using fixed JD coordinate " + searchPoint);
            } else {
                searchPoint = center(searchEntry);
                append("search entry node=" + searchEntry.index());
            }
            InputReceipt entryTap = api.input().tap(target, home, searchPoint);
            append("tap search entry: " + entryTap.status());
            Thread.sleep(300L);

            Observation search = observeReady(api, target, "search");
            UiNode field = findEditable(search);
            if (field == null) {
                throw new IllegalStateException("search field is not editable");
            }
            String text = "短袖T恤 男款 XL";
            InputReceipt input = api.input().setText(target, search, field.ref(), text);
            append("setText: " + input.status() + " verified=" + input.verified());
            if (input.status() != InputStatus.VERIFIED) {
                throw new IllegalStateException("setText was not verified");
            }
            Thread.sleep(200L);

            Observation typed = observeReady(api, target, "typed");
            UiNode typedField = findEditable(typed);
            append("field text='" + (typedField == null ? "<missing>" : typedField.text()) + "'");

            UiNode searchButton = findSearchButton(typed);
            if (searchButton == null) {
                throw new IllegalStateException("search button not found");
            }
            InputReceipt submit = api.input().tap(target, typed, center(searchButton));
            append("tap search: " + submit.status());
            Thread.sleep(600L);

            Observation results = observeReady(api, target, "results");
            append("results nodes=" + results.nodes().size());

            TargetInfo main = api.tasks().moveToMain(target);
            append("moveToMain display=" + main.displayId());
            try {
                api.input().tap(target, results, new Point(10, 10));
                throw new IllegalStateException("main-screen agent input was not rejected");
            } catch (DeviceException expected) {
                append("main-screen input rejected: " + expected.code());
                if (expected.code() != DeviceException.Code.REJECTED) {
                    throw expected;
                }
            }

            TargetInfo back = api.tasks().moveToPrivate(target, display);
            append("moveToPrivate display=" + back.displayId());
            Thread.sleep(300L);
            Observation restored = observeReady(api, target, "restored");
            append("restored nodes=" + restored.nodes().size());
            append("SMOKE PASS");
        } catch (Throwable failure) {
            Log.e(TAG, "smoke failed", failure);
            append("SMOKE FAIL: " + failure);
        } finally {
            if (target != null) {
                try {
                    api.targets().release(target);
                    append("target released");
                } catch (Throwable failure) {
                    append("release target: " + failure);
                }
            }
            if (display != null) {
                try {
                    api.displays().release(display);
                    append("display released");
                } catch (Throwable failure) {
                    append("release display: " + failure);
                }
            }
            if (api != null) {
                api.close();
            }
            running = false;
            append("--- smoke end ---");
        }
    }

    private Observation observeReady(BlockingDeviceApi api, TargetHandle target, String label)
            throws Exception {
        Observation last = null;
        for (int attempt = 1; attempt <= 6; attempt++) {
            last = api.observations().observe(target, ObservationOptions.defaults());
            append(label + ": " + last.status() + " nodes=" + last.nodes().size()
                    + " window=" + last.windowId());
            if (last.status() == ObservationStatus.READY) {
                return last;
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException(label + " did not become READY: "
                + (last == null ? "no observation" : last.status()));
    }

    private static UiNode findSearchEntry(Observation observation) {
        for (UiNode node : observation.nodes()) {
            if (node.hasFlag(NodeFlag.CLICKABLE) && containsSearch(node)) {
                return node;
            }
        }
        for (UiNode node : observation.nodes()) {
            if (containsSearch(node)) {
                return node;
            }
        }
        // JD 16.1 draws the search bar as a wide custom clickable view with no
        // accessibility text. Keep this heuristic scoped to the top area.
        for (UiNode node : observation.nodes()) {
            if (node.hasFlag(NodeFlag.CLICKABLE)
                    && node.bounds().top() < 400
                    && node.bounds().width() > 300
                    && node.bounds().height() < 200) {
                return node;
            }
        }
        return null;
    }

    private static boolean containsSearch(UiNode node) {
        return node.contentDescription().contains("搜索")
                || node.text().contains("搜索")
                || node.viewId().toLowerCase().contains("search");
    }

    private static UiNode findEditable(Observation observation) {
        for (UiNode node : observation.nodes()) {
            if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                return node;
            }
        }
        return null;
    }

    private static UiNode findSearchButton(Observation observation) {
        for (UiNode node : observation.nodes()) {
            if (node.hasFlag(NodeFlag.CLICKABLE) && "搜索".equals(node.text().trim())) {
                return node;
            }
        }
        return null;
    }

    private static Point center(UiNode node) {
        return new Point(node.bounds().centerX(), node.bounds().centerY());
    }

    private void saveFrame(Frame frame) {
        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            File file = new File(dir, "smoke-frame.png");
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(frame.png());
            }
            append("frame saved: " + file.getAbsolutePath());
        } catch (Throwable failure) {
            append("frame save failed: " + failure);
        }
    }

    private static final class NoOpPolicy implements LoopPolicy {

        @Override
        public Optional<LoopAction> next(LoopContext context) {
            return Optional.of(new NoOpAction());
        }
    }

    private static final class NoOpAction implements LoopAction {

        @Override
        public String id() {
            return "no-op";
        }

        @Override
        public String description() {
            return "deliberate no-op for slow-loop smoke";
        }

        @Override
        public agent.next.brain.ActionKind kind() {
            return agent.next.brain.ActionKind.NAVIGATE;
        }

        @Override
        public RiskClass risk() {
            return RiskClass.LOW;
        }

        @Override
        public String payloadHash() {
            return "no-op";
        }

        @Override
        public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
                throws DeviceException {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DeviceException(DeviceException.Code.INTERNAL,
                        "no-op interrupted", interrupted);
            }
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }
    }

    private static final class JdSearchPolicy implements LoopPolicy {

        @Override
        public boolean complete(LoopContext context) {
            StringBuilder text = new StringBuilder();
            for (UiNode node : context.observation().nodes()) {
                if (!node.text().isEmpty()) {
                    text.append(node.text()).append('|');
                }
                String value = node.text();
                if (value.contains("¥") || value.contains("综合推荐")
                        || value.contains("销量") || value.contains("筛选")
                        || value.contains("今日上新") || value.contains("奥莱")
                        || value.contains("明日达")) {
                    Log.i(TAG, "policy complete: " + summarize(context.observation()));
                    return true;
                }
            }
            Log.i(TAG, "policy not complete: " + text);
            return false;
        }

        @Override
        public Optional<LoopAction> next(LoopContext context) {
            UiNode editable = null;
            for (UiNode node : context.observation().nodes()) {
                if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                    editable = node;
                    break;
                }
            }
            if (editable == null) {
                if (hasQuery(context.observation())) {
                    Log.i(TAG, "policy next: wait for results; " + summarize(context.observation()));
                    return Optional.of(new WaitAction("wait-results", "wait for JD results"));
                }
                // JD 16.1's home search bar is not exposed as an accessible node.
                Log.i(TAG, "policy next: home, tap search bar; " + summarize(context.observation()));
                return Optional.of(new TapPointAction("open-search", "tap JD search bar",
                        new Point(360, 180)));
            }
            if (editable.text().isEmpty()) {
                Log.i(TAG, "policy next: type query; " + summarize(context.observation()));
                return Optional.of(new SetTextAction("type", "type JD query",
                        NodeSelector.editable(), "短袖T恤 男款 XL",
                        RiskClass.REVERSIBLE_SIDE_EFFECT));
            }
            for (UiNode node : context.observation().nodes()) {
                if (node.hasFlag(NodeFlag.CLICKABLE) && node.text().contains("搜索")) {
                    Log.i(TAG, "policy next: submit; " + summarize(context.observation()));
                    return Optional.of(new TapAction("submit", "submit JD search",
                            NodeSelector.clickableTextContains("搜索"), RiskClass.LOW));
                }
            }
            Log.i(TAG, "policy next: point submit; " + summarize(context.observation()));
            return Optional.of(new TapPointAction("submit-point", "tap JD search button",
                    new Point(660, 85)));
        }

        private static boolean hasQuery(Observation observation) {
            for (UiNode node : observation.nodes()) {
                if (node.text().contains("短袖T恤")) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class WaitAction implements LoopAction {

        private final String id;
        private final String description;

        private WaitAction(String id, String description) {
            this.id = id;
            this.description = description;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public agent.next.brain.ActionKind kind() {
            return agent.next.brain.ActionKind.NAVIGATE;
        }

        @Override
        public RiskClass risk() {
            return RiskClass.LOW;
        }

        @Override
        public String payloadHash() {
            return Integer.toHexString((id + "|" + description).hashCode());
        }

        @Override
        public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
                throws DeviceException {
            try {
                Thread.sleep(700L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new DeviceException(DeviceException.Code.INTERNAL,
                        "wait interrupted", interrupted);
            }
            return new InputReceipt(InputStatus.INJECTED, true, false, null, Instant.now());
        }
    }

    private static final class TapPointAction implements LoopAction {

        private final String id;
        private final String description;
        private final Point point;

        private TapPointAction(String id, String description, Point point) {
            this.id = id;
            this.description = description;
            this.point = point;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String description() {
            return description;
        }

        @Override
        public agent.next.brain.ActionKind kind() {
            return agent.next.brain.ActionKind.NAVIGATE;
        }

        @Override
        public RiskClass risk() {
            return RiskClass.LOW;
        }

        @Override
        public String payloadHash() {
            return Integer.toHexString((id + "|" + description + "|" + point).hashCode());
        }

        @Override
        public InputReceipt execute(DeviceApi api, TargetHandle target, Observation observation)
                throws DeviceException {
            return api.input().tap(target, observation, point);
        }
    }

    private static final class UnavailableHumanGateway implements HumanGateway {

        @Override
        public ConfirmationToken confirm(ConfirmationRequest request)
                throws HumanUnavailableException {
            throw new HumanUnavailableException("shell has no human gateway");
        }

        @Override
        public HandoffSession handoff(HandoffRequest request) throws HumanUnavailableException {
            throw new HumanUnavailableException("shell has no human gateway");
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

    private static List<String> visibleTexts(Observation observation) {
        List<String> result = new ArrayList<>();
        for (UiNode node : observation.nodes()) {
            if (!node.text().isEmpty() && result.size() < 30) {
                result.add(node.text());
            }
        }
        return result;
    }

    private static String summarize(Observation observation) {
        StringBuilder result = new StringBuilder();
        result.append(observation.status()).append(" nodes=").append(observation.nodes().size())
                .append(" texts=");
        int count = 0;
        for (UiNode node : observation.nodes()) {
            if (!node.text().isEmpty() && count++ < 20) {
                result.append(node.text()).append('|');
            }
        }
        return result.toString();
    }

    private void append(String line) {
        Log.i(TAG, line);
        main.post(() -> {
            if (log != null) {
                log.append(line);
                log.append("\n");
            }
            if (scroll != null) {
                scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
            }
        });
    }
}
