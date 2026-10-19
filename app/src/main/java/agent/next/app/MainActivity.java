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
import agent.next.brain.plan.PlanningResult;
import agent.next.brain.plan.TaskPlan;
import agent.next.brain.session.SessionResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Product task surface: natural-language plan, human plan review, run and stop. */
public final class MainActivity extends Activity implements AgentRuntime.Listener {

    private static final String QUARK = "com.quark.browser";
    private static final String JD = "com.jingdong.app.mall";
    private static final String CALENDAR = "com.android.calendar";

    private AgentRuntime runtime;
    private EditText goal;
    private Spinner primary;
    private TextView planView;
    private TextView status;
    private TextView log;
    private Button planButton;
    private Button runButton;
    private Button stopButton;
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
        if (getIntent().getBooleanExtra("soak", false)) {
            AgentTaskService.startSoak(this, 20);
        }
        runtime = AgentRuntime.get(this);
        runtime.setListener(this);
        runtime.setConfirmationPresenter(this::askConfirmation);
        if (Build.VERSION.SDK_INT >= 33) {
            boolean needPost = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED;
            boolean needCalendar = checkSelfPermission(Manifest.permission.READ_CALENDAR)
                    != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.WRITE_CALENDAR)
                    != PackageManager.PERMISSION_GRANTED;
            if (needPost || needCalendar) {
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS,
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR}, 41);
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
        hint.setText("用一句话描述任务。先确认计划，再让 Agent 操作；高风险动作会再次询问。");
        hint.setTextSize(14);
        root.addView(hint);

        goal = new EditText(this);
        goal.setMinLines(3);
        goal.setText("查看京东首页，告诉我你看到了什么商品");
        root.addView(goal, matchWrap());

        TextView primaryLabel = new TextView(this);
        primaryLabel.setText("主 App（计划第一段）");
        root.addView(primaryLabel);

        primary = new Spinner(this);
        loadInstalledApps();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels);
        primary.setAdapter(adapter);
        root.addView(primary, matchWrap());

        planButton = new Button(this);
        planButton.setText("生成计划");
        planButton.setOnClickListener(view -> requestPlan());
        root.addView(planButton, matchWrap());

        planView = new TextView(this);
        planView.setText("尚未生成计划。");
        planView.setTextSize(14);
        planView.setPadding(0, padding, 0, padding);
        root.addView(planView, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        runButton = new Button(this);
        runButton.setText("确认并执行");
        runButton.setEnabled(false);
        runButton.setOnClickListener(view -> {
            AgentTaskService.start(this);
            runtime.runPlan();
        });
        stopButton = new Button(this);
        stopButton.setText("停止");
        stopButton.setOnClickListener(view -> runtime.stop());
        actions.addView(runButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        actions.addView(stopButton, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(actions, matchWrap());

        status = new TextView(this);
        status.setText("空闲");
        status.setTextSize(14);
        status.setPadding(0, padding, 0, 0);
        root.addView(status, matchWrap());

        log = new TextView(this);
        log.setTextSize(12);
        log.setTextColor(Color.DKGRAY);
        root.addView(log, matchWrap());

        Button preview = new Button(this);
        preview.setText("打开预览 / 接管");
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
        addIfInstalled(QUARK, "Quark");
        addIfInstalled(CALENDAR, "日历");
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

    private void requestPlan() {
        String text = goal.getText().toString().trim();
        if (text.isEmpty() || packages.isEmpty()) {
            append("请输入任务并确保至少一个 App 已安装");
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
                List.of("只使用已安装 App", "不自动支付/下单/发送/删除"),
                allowed,
                installed,
                primaryPackage,
                Set.of("payment", "place_order", "send", "delete"),
                Set.of("visual", "calendar.event", "jd.cart"),
                AutonomyGrant.of(AutonomyLevel.A3_CONFIRM_COMMIT));
        planView.setText("规划中…");
        runButton.setEnabled(false);
        AgentTaskService.start(this);
        runtime.plan(request);
    }

    @Override
    public void onState(AgentRuntime.State state, String detail) {
        status.setText(state + " · " + detail);
        AgentTaskService.update(this, state + " · " + detail);
        if (state == AgentRuntime.State.DONE || state == AgentRuntime.State.STOPPED
                || state == AgentRuntime.State.FAILED || state == AgentRuntime.State.NEED_USER
                || state == AgentRuntime.State.AWAITING_PLAN) {
            AgentTaskService.stop(this);
        }
        append("state: " + state + " · " + detail);
        stopButton.setEnabled(state == AgentRuntime.State.RUNNING);
        planButton.setEnabled(state == AgentRuntime.State.IDLE
                || state == AgentRuntime.State.DONE
                || state == AgentRuntime.State.FAILED
                || state == AgentRuntime.State.STOPPED
                || state == AgentRuntime.State.NEED_USER);
    }

    @Override
    public void onPlan(PlanningResult result) {
        if (result.needsClarification()) {
            planView.setText("需要澄清：" + result.clarification());
            runButton.setEnabled(false);
            return;
        }
        TaskPlan plan = result.plan();
        StringBuilder summary = new StringBuilder("计划：").append(plan.task().goal()).append('\n');
        for (int index = 0; index < plan.subgoals().size(); index++) {
            var subgoal = plan.subgoals().get(index);
            summary.append(index + 1).append(". [").append(subgoal.targetPackage())
                    .append("] ").append(subgoal.intent())
                    .append(" (").append(subgoal.verifierId()).append(")\n");
            for (var condition : subgoal.postconditions()) {
                summary.append("   - ").append(condition.description()).append("\n");
            }
        }
        summary.append("成功标准：\n");
        for (var criterion : plan.task().successCriteria()) {
            summary.append("- ").append(criterion.description()).append('\n');
        }
        summary.append("执行时仍需逐步确认高风险动作；计划本身确认后才开始。");
        planView.setText(summary);
        runButton.setEnabled(true);
    }

    @Override
    public void onSessionResult(SessionResult result) {
        AgentTaskService.stop(this);
        append("result: " + result.status() + " · " + result.detail()
                + " actions=" + result.actions() + " attempts=" + result.attempts());
        runButton.setEnabled(false);
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
        if (log == null) {
            android.util.Log.i("AgentNext", "early log: " + line);
            return;
        }
        log.append(line);
        log.append("\n");
    }
}
