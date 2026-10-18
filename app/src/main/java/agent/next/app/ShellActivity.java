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
import agent.next.device.DisplayHandle;
import agent.next.device.DisplaySpec;
import agent.next.device.Frame;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.ObservationStatus;
import agent.next.device.TargetHandle;
import agent.next.device.TargetSpec;
import agent.next.device.UiNode;
import agent.phone.capability.BlockingDeviceApi;
import agent.phone.connection.ShizukuConnectionDriver;

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

            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            append("observe: " + observation.status()
                    + " nodes=" + observation.nodes().size()
                    + " window=" + observation.windowId()
                    + " activity='" + observation.activityName() + "'");
            if (observation.status() == ObservationStatus.READY) {
                int editable = 0;
                int clickable = 0;
                for (UiNode node : observation.nodes()) {
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
                append("SMOKE PASS");
            } else {
                append("SMOKE FAIL: observation status " + observation.status());
            }
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
