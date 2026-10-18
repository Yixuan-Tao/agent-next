package agent.next.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import agent.next.device.DeviceException;
import agent.next.device.DisplayHandle;
import agent.next.device.DisplaySpec;
import agent.next.device.TargetHandle;
import agent.next.device.TargetSpec;
import agent.phone.capability.BlockingDeviceApi;
import agent.phone.connection.ShizukuConnectionDriver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Standalone real-device preview/touch smoke; product runtime integration comes later. */
public final class PreviewActivity extends Activity {

    private static final String TAG = "AgentNextPreview";
    private static final String TARGET_PACKAGE = "com.jingdong.app.mall";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile BlockingDeviceApi api;
    private volatile DisplayHandle display;
    private volatile TargetHandle target;
    private volatile Surface surface;
    private volatile boolean previewStarted;

    private TextureView video;
    private TextView status;
    private float downX;
    private float downY;
    private long downAt;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        worker.execute(this::connect);
    }

    @Override
    protected void onDestroy() {
        worker.execute(this::cleanup);
        worker.shutdown();
        super.onDestroy();
    }

    private ViewGroup buildUi() {
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("AgentNext Preview · private display");
        title.setTextColor(Color.LTGRAY);
        title.setTextSize(16);
        root.addView(title);

        video = new TextureView(this);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(720, 1280);
                surface = new Surface(texture);
                startPreviewIfReady();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                Surface old = surface;
                surface = null;
                if (old != null) {
                    old.release();
                }
                stopPreview();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
        video.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX();
                downY = event.getY();
                downAt = event.getEventTime();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                sendTouch((TextureView) view, event);
                return true;
            }
            return true;
        });
        root.addView(video, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setText("连接并启动京东…");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(13);
        root.addView(status);

        Button mainScreen = new Button(this);
        mainScreen.setText("Main-screen takeover");
        mainScreen.setOnClickListener(view -> moveToMain());
        root.addView(mainScreen);

        Button backPrivate = new Button(this);
        backPrivate.setText("Return to private display");
        backPrivate.setOnClickListener(view -> moveToPrivate());
        root.addView(backPrivate);

        Button close = new Button(this);
        close.setText("Close and release");
        close.setOnClickListener(view -> finish());
        root.addView(close);
        root.setGravity(Gravity.TOP);
        return root;
    }

    private void connect() {
        BlockingDeviceApi created = null;
        try {
            created = new BlockingDeviceApi(
                    listener -> new ShizukuConnectionDriver(getApplicationContext(), listener),
                    60_000L);
            api = created;
            post("connect");
            created.connect();
            display = created.displays().create(DisplaySpec.standard());
            target = created.targets().open(display,
                    TargetSpec.single("preview-task", TARGET_PACKAGE));
            created.observations().observe(target,
                    agent.next.device.ObservationOptions.defaults());
            post("connected: display=" + display.token() + " target=" + target.token());
            main.post(this::startPreviewIfReady);
        } catch (Throwable failure) {
            Log.e(TAG, "preview connect failed", failure);
            post("preview failed: " + failure);
            if (created != null) {
                created.close();
            }
        }
    }

    private void startPreviewIfReady() {
        BlockingDeviceApi current = api;
        DisplayHandle currentDisplay = display;
        Surface currentSurface = surface;
        if (current == null || currentDisplay == null || currentSurface == null
                || !currentSurface.isValid() || previewStarted) {
            return;
        }
        worker.execute(() -> {
            try {
                current.startPreview(currentDisplay, currentSurface);
                previewStarted = true;
                post("preview mirror started; touch the video to inject user tap/swipe");
            } catch (Throwable failure) {
                Log.e(TAG, "start preview failed", failure);
                post("preview start failed: " + failure);
            }
        });
    }

    private void stopPreview() {
        BlockingDeviceApi current = api;
        DisplayHandle currentDisplay = display;
        if (current == null || currentDisplay == null || !previewStarted) {
            return;
        }
        worker.execute(() -> {
            try {
                current.stopPreview(currentDisplay);
            } catch (Throwable failure) {
                Log.w(TAG, "stop preview failed", failure);
            } finally {
                previewStarted = false;
            }
        });
    }

    private void sendTouch(TextureView view, MotionEvent event) {
        BlockingDeviceApi current = api;
        TargetHandle currentTarget = target;
        if (current == null || currentTarget == null) {
            return;
        }
        int width = Math.max(1, view.getWidth());
        int height = Math.max(1, view.getHeight());
        int x = Math.max(0, Math.min(719, Math.round(event.getX() * 720f / width)));
        int y = Math.max(0, Math.min(1279, Math.round(event.getY() * 1280f / height)));
        int x1 = Math.max(0, Math.min(719, Math.round(downX * 720f / width)));
        int y1 = Math.max(0, Math.min(1279, Math.round(downY * 1280f / height)));
        int duration = (int) Math.max(100, Math.min(2000, event.getEventTime() - downAt));
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        worker.execute(() -> {
            try {
                if (dx * dx + dy * dy < 24f * 24f) {
                    current.userTap(currentTarget, x, y);
                    post("user tap " + x + "," + y);
                } else {
                    current.userSwipe(currentTarget, x1, y1, x, y, duration);
                    post("user swipe " + x1 + "," + y1 + " -> " + x + "," + y);
                }
            } catch (DeviceException failure) {
                post("user touch failed: " + failure.code());
            }
        });
    }

    private void moveToMain() {
        BlockingDeviceApi current = api;
        TargetHandle currentTarget = target;
        if (current == null || currentTarget == null) {
            return;
        }
        stopPreview();
        worker.execute(() -> {
            try {
                current.tasks().moveToMain(currentTarget);
                post("target moved to main screen; use system UI, then return");
            } catch (DeviceException failure) {
                post("main-screen move failed: " + failure.code());
            }
        });
    }

    private void moveToPrivate() {
        BlockingDeviceApi current = api;
        TargetHandle currentTarget = target;
        DisplayHandle currentDisplay = display;
        if (current == null || currentTarget == null || currentDisplay == null) {
            return;
        }
        worker.execute(() -> {
            try {
                current.tasks().moveToPrivate(currentTarget, currentDisplay);
                current.observations().observe(currentTarget,
                        agent.next.device.ObservationOptions.defaults());
                previewStarted = false;
                post("target returned to private display");
                main.post(this::startPreviewIfReady);
            } catch (DeviceException failure) {
                post("return to private failed: " + failure.code());
            }
        });
    }

    private void cleanup() {
        BlockingDeviceApi current = api;
        DisplayHandle currentDisplay = display;
        TargetHandle currentTarget = target;
        if (current != null) {
            try {
                current.stopPreview(currentDisplay);
            } catch (Throwable ignored) {
            }
            try {
                if (currentTarget != null) {
                    current.targets().release(currentTarget);
                }
            } catch (Throwable ignored) {
            }
            try {
                if (currentDisplay != null) {
                    current.displays().release(currentDisplay);
                }
            } catch (Throwable ignored) {
            }
            current.close();
        }
        Surface old = surface;
        surface = null;
        if (old != null) {
            old.release();
        }
    }

    private void post(String message) {
        Log.i(TAG, message);
        main.post(() -> status.setText(message));
    }
}
