package agent.foreground.test;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class TestActivity extends Activity {
    static volatile String published = "{}";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final JSONArray events = new JSONArray();
    private String session;
    private EditText editor;
    private Button counter;
    private Button hide;
    private ScrollView scroller;
    private TextView status;
    private boolean resumed;
    private long sequence;
    private int clicks, revision, downs, ups;
    private long lastBeat;
    private long maxBeatGap;
    private long lastScrollEvent;
    private final Runnable beat = new Runnable() {
        @Override public void run() {
            long now = SystemClock.uptimeMillis();
            if (lastBeat > 0) maxBeatGap = Math.max(maxBeatGap, now - lastBeat);
            lastBeat = now;
            publish();
            handler.postDelayed(this, 200);
        }
    };

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        session = getIntent().getStringExtra("session");
        if (session == null || !session.matches("[a-f0-9]{32}")) session = UUID.randomUUID().toString().replace("-", "");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff6f7fb);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            root.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right,
                    dp(12) + Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            event("insets");
            return insets;
        });
        TextView title = new TextView(this);
        title.setText("前台隔离测试 · 自动操作中"); title.setTextSize(22);
        root.addView(title);
        status = new TextView(this);
        status.setText("只使用测试文字；异常时停止并保留结果。");
        root.addView(status);
        editor = new EditText(this);
        editor.setHint("软键盘测试输入区");
        // URI input requests literal keys without using a password field or clipboard.
        // https://developer.android.com/reference/android/text/InputType#TYPE_TEXT_VARIATION_URI
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setMinLines(2); editor.setMaxLines(3); editor.setTextSize(18);
        editor.setOnClickListener(v -> editor.setSelection(editor.length()));
        root.addView(editor, new LinearLayout.LayoutParams(-1, dp(110)));
        counter = new Button(this);
        counter.setText("点击计数：0");
        counter.setOnClickListener(v -> { clicks++; counter.setText("点击计数：" + clicks); event("click"); });
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(counter, new LinearLayout.LayoutParams(0, dp(52), 1));
        hide = new Button(this); hide.setText("收起键盘");
        hide.setOnClickListener(v -> {
            // https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/visibility
            getWindow().getInsetsController().hide(WindowInsets.Type.ime()); event("hide_requested");
        });
        buttons.addView(hide, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(buttons);
        scroller = new ScrollView(this);
        LinearLayout rows = new LinearLayout(this); rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 1; i <= 80; i++) {
            TextView row = new TextView(this); row.setText("滚动测试项目 " + i);
            row.setTextSize(18); row.setPadding(dp(12), dp(12), dp(12), dp(12)); rows.addView(row);
        }
        scroller.addView(rows);
        scroller.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastScrollEvent >= 100) { lastScrollEvent = now; event("scroll"); }
        });
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                revision++; event("text");
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        editor.setOnFocusChangeListener((v, focused) -> event("editor_focus"));
        root.getViewTreeObserver().addOnGlobalLayoutListener(this::publish);
        event("create"); handler.post(beat);
    }

    @Override protected void onResume() { super.onResume(); resumed = true; event("resume"); }
    @Override protected void onPause() { resumed = false; event("pause"); super.onPause(); }
    @Override protected void onDestroy() { event("destroy"); handler.removeCallbacks(beat); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); event("window_focus"); }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) downs++;
        if (e.getActionMasked() == MotionEvent.ACTION_UP) ups++;
        return super.dispatchTouchEvent(e);
    }

    private JSONArray bounds(View view) throws org.json.JSONException {
        int[] at = new int[2]; view.getLocationOnScreen(at);
        return new JSONArray(new int[]{at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight()});
    }

    private void event(String kind) {
        try {
            JSONObject row = new JSONObject(); row.put("seq", ++sequence);
            row.put("time", SystemClock.uptimeMillis()); row.put("kind", kind);
            row.put("resumed", resumed); row.put("windowFocus", hasWindowFocus());
            row.put("text", editor == null ? "" : editor.getText().toString());
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            row.put("ime", insets != null && insets.isVisible(WindowInsets.Type.ime()));
            events.put(row);
            if (events.length() > 256) events.remove(0);
            publish();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void publish() {
        if (editor == null || scroller == null) return;
        try {
            JSONObject s = new JSONObject(); s.put("package", getPackageName()); s.put("session", session);
            s.put("pid", Process.myPid()); s.put("display", getDisplay().getDisplayId());
            s.put("resumed", resumed); s.put("windowFocus", hasWindowFocus());
            s.put("editorFocus", editor.hasFocus()); s.put("heartbeat", lastBeat);
            s.put("maxBeatGap", maxBeatGap); s.put("text", editor.getText().toString());
            s.put("clicks", clicks); s.put("revision", revision); s.put("scrollY", scroller.getScrollY());
            s.put("touchDowns", downs); s.put("touchUps", ups);
            s.put("rotation", getDisplay().getRotation()); s.put("seq", sequence); s.put("events", events);
            s.put("editorBounds", bounds(editor)); s.put("counterBounds", bounds(counter));
            s.put("hideBounds", bounds(hide));
            s.put("scrollBounds", bounds(scroller));
            WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
            s.put("ime", insets != null && insets.isVisible(WindowInsets.Type.ime()));
            s.put("imeBottom", insets == null ? 0 : insets.getInsets(WindowInsets.Type.ime()).bottom);
            published = s.toString();
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
