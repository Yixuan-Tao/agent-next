package agent.next.app.skill;

import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.Json;
import agent.next.device.Frame;
import agent.next.device.Point;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Vision-based semantic locator for JD pages that expose no usable accessibility
 * controls (search results). Returns a validated normalized point only; no raw
 * device action is allowed here.
 */
public final class JdVisualLocator {

    private static final int MAX_IMAGE_BYTES = 4_194_304;
    private final ChatCompletionClient client;

    public JdVisualLocator(ChatCompletionClient client) {
        this.client = client;
    }

    public Point locate(Frame frame, String target) throws IOException {
        if (frame == null || frame.png().length == 0 || frame.png().length > MAX_IMAGE_BYTES) {
            throw new IOException("invalid frame");
        }
        String prompt = "你在看一张京东 App 截图。只做视觉定位，不要执行任何操作。\n"
                + "目标：" + target + "\n"
                + "返回一个 JSON 对象，x/y 为相对截图宽高的 0..1 归一化中心点，"
                + "confidence 为 0..1，label 为简短中文说明。\n"
                + "只选择真实可见的目标；如果目标不存在或不确定，返回 "
                + "{\"x\":0,\"y\":0,\"confidence\":0,\"label\":\"not found\"}。\n"
                + "不要选择广告、筛选栏、地址栏、购买/结算按钮。\n"
                + "示例：{\"x\":0.42,\"y\":0.55,\"confidence\":0.9,\"label\":\"第一个商品卡片\"}";
        String raw = client.completeWithImage(prompt, frame.png(), 700);
        Map<String, Object> root = Json.object(stripFences(raw));
        double x = number(root, "x");
        double y = number(root, "y");
        double confidence = number(root, "confidence");
        if (confidence < 0.5 || x <= 0 || x >= 1 || y <= 0 || y >= 1) {
            throw new IOException("vision locator unavailable/unsure");
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
        String value = text.trim();
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
