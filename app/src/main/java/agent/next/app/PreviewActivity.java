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
import android.widget.LinearLayout;
import android.widget.TextView;

/** Preview/takeover over the shared AgentRuntime; pauses the agent on entry. */
public final class PreviewActivity extends Activity {

    private static final String TAG = "AgentNextPreview";

    private AgentRuntime runtime;
    private TextureView video;
    private TextView status;
    private volatile Surface surface;
    private volatile boolean previewStarted;
    private float downX;
    private float downY;
    private long downAt;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(buildUi());
        runtime = AgentRuntime.get(this);
        runtime.pauseForUser();
        if (!runtime.canPreview()) {
            status.setText("当前没有运行中的 target；请先执行任务再打开预览。");
        }
    }

    @Override
    protected void onDestroy() {
        runtime.stopPreview();
        runtime.resumeAfterUser();
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

        TextView title = new TextView(this);
        title.setText("AgentNext Preview · agent paused");
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
                runtime.stopPreview();
                previewStarted = false;
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
        status.setText("正在连接共享 runtime…");
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
        close.setText("Close and resume agent");
        close.setOnClickListener(view -> finish());
        root.addView(close);
        root.setGravity(Gravity.TOP);
        return root;
    }

    private void startPreviewIfReady() {
        Surface currentSurface = surface;
        if (currentSurface == null || !currentSurface.isValid() || previewStarted
                || runtime == null || !runtime.canPreview()) {
            return;
        }
        runtime.startPreview(currentSurface, (ok, message) -> {
            previewStarted = ok;
            post((ok ? "preview mirror started; touch = user takeover" : message));
        });
    }

    private void sendTouch(TextureView view, MotionEvent event) {
        if (!runtime.canPreview()) {
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
        if (dx * dx + dy * dy < 24f * 24f) {
            runtime.previewTap(x, y, (ok, message) -> post(message));
        } else {
            runtime.previewSwipe(x1, y1, x, y, duration, (ok, message) -> post(message));
        }
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
}
