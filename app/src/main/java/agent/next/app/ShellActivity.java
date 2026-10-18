package agent.next.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
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

import agent.next.device.DeviceCapabilities;
import agent.next.device.DeviceException;
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
import agent.phone.connection.ShizukuConnectionDriver;

import java.io.File;
import java.io.FileOutputStream;
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
        if (getIntent().getBooleanExtra("autorun", true)) {
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
        hint.setText("Connect Shizuku, create a 720x1280 private display, launch JD, observe, capture, release.");
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
            Thread.sleep(1_500L);

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
            Thread.sleep(800L);

            Observation typed = observeReady(api, target, "typed");
            UiNode typedField = findEditable(typed);
            append("field text='" + (typedField == null ? "<missing>" : typedField.text()) + "'");

            UiNode searchButton = findSearchButton(typed);
            if (searchButton == null) {
                throw new IllegalStateException("search button not found");
            }
            InputReceipt submit = api.input().tap(target, typed, center(searchButton));
            append("tap search: " + submit.status());
            Thread.sleep(2_000L);

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
            Thread.sleep(1_200L);
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
