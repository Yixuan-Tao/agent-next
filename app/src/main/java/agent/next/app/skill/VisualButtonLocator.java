package agent.next.app.skill;

import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.Json;
import agent.next.device.Frame;
import agent.next.device.Point;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

/** App-agnostic visual button locator: model proposes a point, code validates it. */
public final class VisualButtonLocator {

    private static final int MAX_IMAGE_BYTES = 4_194_304;
    private final ChatCompletionClient client;

    public VisualButtonLocator(ChatCompletionClient client) {
        this.client = client;
    }

    public Point locate(Frame frame, String label) throws IOException {
        if (frame == null || frame.png().length == 0 || frame.png().length > MAX_IMAGE_BYTES) {
            throw new IOException("invalid frame");
        }
        String target = switch (label) {
            case "C（清除）" -> "左上角标有 C 的清除键";
            case "=" -> "右下角橙色等号键";
            case "×" -> "右侧乘号键";
            default -> "键盘上的 " + label + " 键";
        };
        String prompt = "你在看一张 Android 计算器截图。只做视觉定位，不要执行任何操作。\n"
                + "目标控件：" + target + "\n"
                + "返回 JSON：{\"x\":0.0,\"y\":0.0,\"confidence\":0.0}。\n"
                + "x/y 为相对截图宽高的 0..1 归一化中心点；confidence 为 0..1。\n"
                + "只选择底部键盘/按钮区域；不要选择顶部表达式或结果显示区中的数字、历史记录或广告。\n"
                + "找不到目标时返回 {\"x\":0,\"y\":0,\"confidence\":0}。";
        String raw = stripFences(client.completeWithImage(prompt, frame.png(), 1024));
        android.util.Log.i("AgentNextRuntime", "visual raw label=" + label + " " + raw);
        Map<String, Object> root = Json.object(raw);
        double x = number(root, "x");
        double y = number(root, "y");
        double confidence = number(root, "confidence");
        if (x < 0 || x > 1 || y < 0 || y > 1 || confidence < 0.5) {
            throw new IOException("visual locator found no confident candidate");
        }
        int px = (int) Math.round(x * frame.width());
        int py = (int) Math.round(y * frame.height());
        return new Point(Math.max(0, Math.min(frame.width() - 1, px)),
                Math.max(0, Math.min(frame.height() - 1, py)));
    }

    private static double number(Map<String, Object> root, String key) {
        Object value = root.get(key);
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException("missing numeric " + key);
        }
        return number.doubleValue();
    }

    private static String stripFences(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            int first = value.indexOf('\n');
            int last = value.lastIndexOf("```");
            if (first >= 0 && last > first) {
                value = value.substring(first + 1, last).trim();
            }
        }
        return value;
    }
}
