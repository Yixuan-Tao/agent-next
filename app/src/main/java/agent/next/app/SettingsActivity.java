package agent.next.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import agent.next.app.security.EncryptedModelConfigStore;

/** Minimal privacy/settings surface: encrypted-config status and memory deletion. */
public final class SettingsActivity extends Activity {

    private TextView status;
    private AgentRuntime runtime;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        runtime = AgentRuntime.get(this);
        setContentView(buildUi());
        refresh();
    }

    private ViewGroup buildUi() {
        int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("设置与隐私");
        title.setTextSize(24);
        title.setTextColor(Color.BLACK);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, padding, 0, padding);
        root.addView(status, matchWrap());

        Button clear = new Button(this);
        clear.setText("清除 episodic memory");
        clear.setOnClickListener(view -> {
            runtime.clearMemory();
            refresh();
        });
        root.addView(clear, matchWrap());

        TextView privacy = new TextView(this);
        privacy.setText("本地存储：\n"
                + "• 模型 API key 使用 AndroidKeyStore AES-GCM 加密，明文 config 迁移后删除；\n"
                + "• episodic memory 只保存结构化事件，不保存原始截图/完整 UI 树；\n"
                + "• 任务停止/失败不会补发消息或自动支付；\n"
                + "• 目前没有云端账号、分析 SDK 或社区 registry。");
        privacy.setTextSize(13);
        privacy.setPadding(0, padding, 0, 0);
        root.addView(privacy, matchWrap());
        return root;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void refresh() {
        status.setText("model config: "
                + (EncryptedModelConfigStore.hasConfig(this) ? "加密文件可用" : "未导入")
                + "\nepisodic memory: " + runtime.memoryEventCount() + " events");
    }
}
