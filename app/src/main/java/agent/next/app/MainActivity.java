package agent.next.app;

import android.app.Activity;
import android.Manifest;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Build;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import agent.next.brain.AutonomyGrant;
import agent.next.brain.AutonomyLevel;
import agent.next.brain.ConfirmationRequest;
import agent.next.brain.plan.InstalledApp;
import agent.next.brain.plan.PlanningRequest;
import agent.next.brain.session.SessionResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Product task surface: natural-language tasks, progress, pause, stop and explicit takeover. */
public final class MainActivity extends Activity implements AgentRuntime.Listener {

    private static final String QUARK = "com.quark.browser";
    private static final String JD = "com.jingdong.app.mall";
    private static final String XIAOMI_SHOP = "com.xiaomi.shop";
    private static final String CALENDAR = "com.android.calendar";
    private static final String GOOGLE_CALENDAR = "com.google.android.calendar";
    private static final String GEMINI = "com.google.android.apps.bard";
    private static final String GOOGLE_ASSISTANT = "com.google.android.googlequicksearchbox";
    private static final String SETTINGS = "com.android.settings";
    private static final String CLOCK = "com.android.deskclock";
    private static final String NOTES = "com.miui.notes";
    private static final String FILES = "com.android.fileexplorer";
    private static final String GOOGLE_FILES = "com.google.android.apps.nbu.files";
    private static final String GOOGLE_CLOCK = "com.google.android.deskclock";
    private static final String CALCULATOR = "com.miui.calculator";
    private static final String CONTACTS = "com.android.contacts";
    private static final String WEATHER = "com.miui.weather2";

    private AgentRuntime runtime;
    private EditText goal;
    private Spinner primary;
    private TextView resultView;
    private TextView status;
    private Button startButton;
    private Button pauseButton;
    private Button stopButton;
    private LinearLayout clarificationPanel;
    private TextView clarificationText;
    private EditText clarificationAnswer;
    private Button clarificationButton;
    private final List<String> packages = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        String initialGoal = getIntent().getStringExtra("goal");
        if (initialGoal != null && !initialGoal.isBlank()) {
            goal.setText(initialGoal);
        }
        String initialPrimary = getIntent().getStringExtra("primary");
        if (initialPrimary != null) {
            int index = packages.indexOf(initialPrimary);
            if (index >= 0) {
                primary.setSelection(index);
            }
        }
        runtime = AgentRuntime.get(this);
        runtime.setListener(this);
        runtime.setConfirmationPresenter(this::askConfirmation);
        if ((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            runtime.setDemoMonitoringEnabled(getIntent().getBooleanExtra("monitor",
                    runtime.demoMonitoringEnabled()));
        }
        if (Build.VERSION.SDK_INT >= 33) {
            boolean needPost = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED;
            if (needPost) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 41);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (runtime != null) {
            runtime.setListener(null);
            runtime.setConfirmationPresenter(null);
        }
        super.onDestroy();
    }

    private ViewGroup buildUi() {
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("AgentNext");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("用一句话描述任务，提交后直接开始。需要补充信息或本人处理时会提示你。");
        hint.setTextSize(14);
        root.addView(hint);

        if ((getApplicationInfo().flags
                & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            CheckBox monitor = new CheckBox(this);
            monitor.setText("电脑只读监看（调试版，缓存最新画面）");
            monitor.setChecked(AgentRuntime.get(this).demoMonitoringEnabled()
                    || getIntent().getBooleanExtra("monitor", false));
            monitor.setOnCheckedChangeListener((button, enabled) ->
                    AgentRuntime.get(this).setDemoMonitoringEnabled(enabled));
            root.addView(monitor);
        }

        goal = new EditText(this);
        goal.setMinLines(3);
        goal.setText("到京东买小米手环11");
        root.addView(goal, matchWrap());

        TextView primaryLabel = new TextView(this);
        primaryLabel.setText("起始应用");
        root.addView(primaryLabel);

        primary = new Spinner(this);
        loadInstalledApps();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        primary.setAdapter(adapter);
        root.addView(primary, matchWrap());

        startButton = new Button(this);
        startButton.setText("开始任务");
        startButton.setOnClickListener(view -> startTask());
        root.addView(startButton, matchWrap());

        resultView = new TextView(this);
        resultView.setText("任务完成后会在这里汇报结果。");
        resultView.setTextSize(14);
        resultView.setPadding(0, padding, 0, padding);
        root.addView(resultView, matchWrap());

        clarificationPanel = new LinearLayout(this);
        clarificationPanel.setOrientation(LinearLayout.VERTICAL);
        clarificationPanel.setVisibility(android.view.View.GONE);
        clarificationText = new TextView(this);
        clarificationText.setTextSize(16);
        clarificationPanel.addView(clarificationText, matchWrap());
        clarificationAnswer = new EditText(this);
        clarificationAnswer.setHint("补充必要信息");
        clarificationPanel.addView(clarificationAnswer, matchWrap());
        clarificationButton = new Button(this);
        clarificationButton.setText("补充并继续");
        clarificationButton.setOnClickListener(view -> {
            String answer = clarificationAnswer.getText().toString().trim();
            if (answer.isEmpty()) {
                clarificationAnswer.setError("请补充这项信息");
                return;
            }
            clarificationButton.setEnabled(false);
            runtime.answerClarification(answer);
        });
        clarificationPanel.addView(clarificationButton, matchWrap());
        root.addView(clarificationPanel, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        pauseButton = new Button(this);
        pauseButton.setText("暂停");
        pauseButton.setEnabled(false);
        pauseButton.setOnClickListener(view -> {
            if (runtime.isUserPaused()) runtime.resumeTask();
            else runtime.pauseTask();
        });
        stopButton = new Button(this);
        stopButton.setText("停止");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(view -> runtime.stop());
        actions.addView(pauseButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(stopButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions, matchWrap());

        status = new TextView(this);
        status.setText("空闲");
        status.setTextSize(14);
        status.setPadding(0, padding, 0, 0);
        root.addView(status, matchWrap());

        Button preview = new Button(this);
        preview.setText("查看画面（只读）");
        preview.setOnClickListener(view -> startActivity(
                new android.content.Intent(this, PreviewActivity.class)));
        root.addView(preview, matchWrap());

        Button settings = new Button(this);
        settings.setText("设置与隐私");
        settings.setOnClickListener(view ->
                startActivity(new android.content.Intent(this, SettingsActivity.class)));
        root.addView(settings, matchWrap());

        root.setGravity(Gravity.TOP);
        return scroll;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void loadInstalledApps() {
        addIfInstalled(JD, "京东");
        addIfInstalled(XIAOMI_SHOP, "小米商城");
        addIfInstalled(QUARK, "Quark");
        addIfInstalled(CALENDAR, "日历");
        addIfInstalled(GOOGLE_CALENDAR, "Google 日历");
        addIfInstalled(GEMINI, "Gemini");
        addIfInstalled(GOOGLE_ASSISTANT, "Google Assistant");
        addIfInstalled(SETTINGS, "设置");
        addIfInstalled(CLOCK, "时钟");
        addIfInstalled(GOOGLE_CLOCK, "Google 时钟");
        addIfInstalled(NOTES, "备忘录");
        addIfInstalled(FILES, "文件管理");
        addIfInstalled(GOOGLE_FILES, "Google 文件");
        addIfInstalled(CALCULATOR, "计算器");
        addIfInstalled(CONTACTS, "联系人");
        addIfInstalled(WEATHER, "天气");
        if (packages.isEmpty()) {
            labels.add("没有可用的 App");
        }
    }

    private void addIfInstalled(String packageName, String label) {
        try {
            getPackageManager().getPackageInfo(packageName, 0);
            packages.add(packageName);
            labels.add(label + " (" + packageName + ")");
        } catch (PackageManager.NameNotFoundException missing) {
            append("未安装: " + packageName);
        }
    }

    private void startTask() {
        String text = goal.getText().toString().trim();
        if (text.isEmpty() || packages.isEmpty()) {
            goal.setError("请输入任务并确保至少一个应用可用");
            return;
        }
        String primaryPackage = packages.get(Math.max(0,
                Math.min(primary.getSelectedItemPosition(), packages.size() - 1)));
        Set<String> allowed = new LinkedHashSet<>(packages);
        List<InstalledApp> installed = new ArrayList<>();
        for (int index = 0; index < packages.size(); index++) {
            installed.add(new InstalledApp(packages.get(index), labels.get(index)));
        }
        PlanningRequest request = new PlanningRequest(
                "task-" + System.currentTimeMillis(),
                text,
                List.of("只使用已安装 App", "不自动支付/下单/发送"),
                allowed,
                installed,
                primaryPackage,
                Set.of("payment", "place_order", "send"),
                Set.of("visual"),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT));
        resultView.setText("");
        runtime.startTask(request);
    }

    @Override
    public void onState(AgentRuntime.State state, String detail) {
        boolean preparing = state == AgentRuntime.State.PLANNING;
        boolean running = state == AgentRuntime.State.RUNNING;
        boolean handoff = runtime.waitingForHandoff();
        status.setText(runtime.isPaused() && (preparing || running || handoff)
                ? "已暂停" : detail.isBlank() ? "准备就绪" : detail);
        boolean busy = preparing || running || handoff || state == AgentRuntime.State.STOPPING;
        startButton.setEnabled(!busy);
        goal.setEnabled(!busy);
        primary.setEnabled(!busy);
        pauseButton.setEnabled((preparing || running) && !handoff);
        pauseButton.setText(runtime.isUserPaused() ? "继续" : "暂停");
        stopButton.setEnabled(busy || state == AgentRuntime.State.NEEDS_INPUT
                || state == AgentRuntime.State.DONE && runtime.canPreview());
        if (state != AgentRuntime.State.NEEDS_INPUT) clarificationPanel.setVisibility(android.view.View.GONE);
        if (state == AgentRuntime.State.FAILED || state == AgentRuntime.State.STOPPED
                || state == AgentRuntime.State.PARTIAL || state == AgentRuntime.State.DONE) resultView.setText(detail);
        append("state: " + state + " · " + detail);
    }

    @Override
    public void onClarification(String question) {
        clarificationText.setText(question);
        clarificationAnswer.setText("");
        clarificationButton.setEnabled(true);
        clarificationPanel.setVisibility(android.view.View.VISIBLE);
    }

    @Override
    public void onSessionResult(SessionResult result) {
        status.setText(result.status() == SessionResult.Status.DONE ? "任务已完成" : "任务已结束");
        resultView.setText(result.detail());
        append("result: " + result.status() + " · " + result.detail()
                + " actions=" + result.actions() + " attempts=" + result.attempts());
    }

    private void askConfirmation(ConfirmationRequest request,
            java.util.function.Consumer<Boolean> decision) {
        if (isFinishing() || isDestroyed()) {
            decision.accept(false);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认动作")
                .setMessage(request.summary() + "\n\n" + request.consequences()
                        + "\n\n可逆: " + request.reversible())
                .setCancelable(false)
                .setPositiveButton("允许本次", (dialog, which) -> decision.accept(true))
                .setNegativeButton("拒绝", (dialog, which) -> decision.accept(false))
                .show();
    }

    private void append(String line) {
        android.util.Log.i("AgentNext", line);
    }
}
