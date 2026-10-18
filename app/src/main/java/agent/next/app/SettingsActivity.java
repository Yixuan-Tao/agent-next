package agent.next.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import agent.next.app.security.EncryptedModelConfigStore;
import agent.next.brain.model.ModelConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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

        Button importConfig = new Button(this);
        importConfig.setText("导入 model config JSON");
        importConfig.setOnClickListener(view -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("application/json");
            startActivityForResult(pick, 100);
        });
        root.addView(importConfig, matchWrap());

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 100 || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                status.setText("导入失败：无法读取文件");
                return;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 65_536) {
                    status.setText("导入失败：config 过大");
                    return;
                }
                output.write(buffer, 0, read);
            }
            String json = output.toString(StandardCharsets.UTF_8);
            Optional<ModelConfig> config = ModelConfig.fromJson(json);
            if (config.isEmpty()) {
                status.setText("导入失败：config 格式无效");
                return;
            }
            EncryptedModelConfigStore.save(this, json);
            refresh();
            status.setText(status.getText() + "\n导入并加密成功");
        } catch (Exception failure) {
            status.setText("导入失败：" + failure.getClass().getSimpleName());
        }
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
