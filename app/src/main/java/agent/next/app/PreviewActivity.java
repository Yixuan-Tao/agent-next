package agent.next.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import agent.next.device.DisplaySpec;

/** Passive preview by default; only an explicit takeover request pauses the agent. */
public final class PreviewActivity extends Activity {

    private static final String TAG = "AgentNextPreview";
    private static final DisplaySpec DISPLAY_SPEC = DisplaySpec.standard();

    private AgentRuntime runtime;
    private TextureView video;
    private TextView status;
    private volatile Surface surface;
    private volatile boolean previewStarted;
    private volatile boolean userTakeoverRequested;
    private boolean previewStarting;
    private final Object previewStartLock = new Object();
    private TextView title;
    private Button mainScreen;
    private Button backPrivate;
    private float downX;
    private float downY;
    private long downAt;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        runtime = AgentRuntime.get(this);
        if (!runtime.canPreview()) {
            status.setText("当前没有运行中的 target；Agent不会因打开查看页而暂停。");
        } else {
            status.setText("正在打开只读画面；Agent继续运行。");
            video.post(this::startPreviewIfReady);
        }
    }

    @Override
    protected void onDestroy() {
        // Resume only after the service confirms the mirror is stopped and the target muted
        // again. On failure the agent stays paused instead of continuing audibly.
        if (userTakeoverRequested) {
            runtime.stopPreview((ok, message) -> {
                if (ok) runtime.resumeAfterUser();
                else showStopFailure(message);
            });
        } else {
            runtime.stopReadOnlyPreview((ok, message) -> {
                if (!ok) showStopFailure(message);
            });
        }
        Surface old = surface;
        surface = null;
        if (old != null) {
            old.release();
        }
        super.onDestroy();
    }

    private ViewGroup buildUi() {
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);
        root.setBackgroundColor(Color.BLACK);

        title = new TextView(this);
        title.setText("任务画面 · 只读查看，Agent继续运行");
        title.setTextColor(Color.LTGRAY);
        title.setTextSize(16);
        root.addView(title);

        FrameLayout viewport = new FrameLayout(this);
        video = new TextureView(this);
        video.setLayoutParams(new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        viewport.addView(video);
        viewport.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> fitVideo(viewport));
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(DISPLAY_SPEC.width(), DISPLAY_SPEC.height());
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
                if (userTakeoverRequested) runtime.stopPreview();
                else runtime.stopReadOnlyPreview(null);
                previewStarted = false;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
        video.setOnTouchListener((view, event) -> {
            if (!userTakeoverRequested || !previewStarted) return true;
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
        root.addView(viewport, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = new TextView(this);
        status.setText("正在连接共享 runtime…");
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(13);
        root.addView(status);

        Button takeover = new Button(this);
        takeover.setText("接管操作（暂停 Agent）");
        takeover.setOnClickListener(view -> requestTakeover());
        root.addView(takeover);

        mainScreen = new Button(this);
        mainScreen.setText("在手机主屏接管");
        mainScreen.setEnabled(false);
        mainScreen.setOnClickListener(view -> moveToMain());
        root.addView(mainScreen);

        backPrivate = new Button(this);
        backPrivate.setText("返回隔离屏");
        backPrivate.setEnabled(false);
        backPrivate.setOnClickListener(view -> moveToPrivate());
        root.addView(backPrivate);

        Button close = new Button(this);
        close.setText("关闭查看页（Agent继续运行）");
        close.setOnClickListener(view -> finish());
        root.addView(close);

        Button release = new Button(this);
        release.setText("释放画面");
        release.setOnClickListener(view -> {
            release.setEnabled(false);
            runtime.releaseRetainedScreen((ok, message) -> {
                post(message);
                if (ok) finish();
                else release.setEnabled(true);
            });
        });
        root.addView(release);
        root.setGravity(Gravity.TOP);
        return root;
    }

    private void fitVideo(FrameLayout viewport) {
        int availableWidth = viewport.getWidth() - viewport.getPaddingLeft()
                - viewport.getPaddingRight();
        int availableHeight = viewport.getHeight() - viewport.getPaddingTop()
                - viewport.getPaddingBottom();
        if (availableWidth <= 0 || availableHeight <= 0) return;

        float sourceWidth = DISPLAY_SPEC.width();
        float sourceHeight = DISPLAY_SPEC.height();
        float scale = Math.min(availableWidth / sourceWidth, availableHeight / sourceHeight);
        int width = Math.max(1, Math.round(sourceWidth * scale));
        int height = Math.max(1, Math.round(sourceHeight * scale));
        ViewGroup.LayoutParams current = video.getLayoutParams();
        if (current.width != width || current.height != height) {
            current.width = width;
            current.height = height;
            video.setLayoutParams(current);
        }
    }

    private void startPreviewIfReady() {
        Surface currentSurface = surface;
        synchronized (previewStartLock) {
            if (currentSurface == null || !currentSurface.isValid() || previewStarted
                    || previewStarting || runtime == null || !runtime.canPreview()
                    || isFinishing() || isDestroyed()) {
                return;
            }
            previewStarting = true;
        }
        post("等待设备空闲，正在打开只读画面…");
        AgentRuntime.PreviewCallback callback = (ok, message) -> {
            synchronized (previewStartLock) {
                previewStarting = false;
                previewStarted = ok;
            }
            post(message);
        };
        if (userTakeoverRequested) runtime.startPreview(currentSurface, callback);
        else runtime.startReadOnlyPreview(currentSurface, callback);
    }

    private void requestTakeover() {
        if (userTakeoverRequested || surface == null || !surface.isValid()) return;
        userTakeoverRequested = true;
        mainScreen.setEnabled(true);
        backPrivate.setEnabled(true);
        title.setText("任务画面 · 用户接管，Agent已暂停");
        post("正在交接操作并暂停 Agent…");
        runtime.pauseForUser();
        previewStarted = false;
        runtime.stopReadOnlyPreview((stopped, message) -> {
            if (!stopped) {
                userTakeoverRequested = false;
                runtime.resumeAfterUser();
                post(message);
                return;
            }
            runtime.startPreview(surface, (ok, startedMessage) -> {
                previewStarted = ok;
                post(ok ? "已接管；触摸画面可操作 App。" : startedMessage);
            });
        });
    }

    private void sendTouch(TextureView view, MotionEvent event) {
        if (!userTakeoverRequested || !previewStarted || !runtime.canPreview()) {
            return;
        }
        int width = Math.max(1, view.getWidth());
        int height = Math.max(1, view.getHeight());
        int x = sourceCoordinate(event.getX(), width, DISPLAY_SPEC.width());
        int y = sourceCoordinate(event.getY(), height, DISPLAY_SPEC.height());
        int x1 = sourceCoordinate(downX, width, DISPLAY_SPEC.width());
        int y1 = sourceCoordinate(downY, height, DISPLAY_SPEC.height());
        int duration = (int) Math.max(100, Math.min(2000, event.getEventTime() - downAt));
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        if (dx * dx + dy * dy < 24f * 24f) {
            runtime.previewTap(x, y, (ok, message) -> post(message));
        } else {
            runtime.previewSwipe(x1, y1, x, y, duration, (ok, message) -> post(message));
        }
    }

    private static int sourceCoordinate(float displayedCoordinate, int displayedSize, int sourceSize) {
        return Math.max(0, Math.min(sourceSize - 1,
                Math.round(displayedCoordinate * sourceSize / displayedSize)));
    }

    private void moveToMain() {
        runtime.stopPreview();
        previewStarted = false;
        runtime.moveToMain((ok, message) -> post(message));
    }

    private void moveToPrivate() {
        runtime.moveToPrivate((ok, message) -> {
            post(message);
            if (ok) {
                startPreviewIfReady();
            }
        });
    }

    private void post(String message) {
        Log.i(TAG, message);
        status.setText(message);
    }

    private void showStopFailure(String message) {
        Log.w(TAG, "preview stop unconfirmed: " + message);
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }
}
