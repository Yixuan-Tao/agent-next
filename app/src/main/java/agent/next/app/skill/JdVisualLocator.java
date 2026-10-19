























package agent.next.app.skill;

import android.util.Log;

import agent.next.brain.grounding.VisualCandidate;
import agent.next.brain.grounding.VisualCandidateFilter;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.Json;
import agent.next.device.Frame;
import agent.next.device.Point;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Vision-based semantic locator for JD pages that expose no usable accessibility
 * controls. It returns only validated normalized candidates; execution is done by
 * DeviceApi, never by the model.
 */
public final class JdVisualLocator {

    private static final int MAX_IMAGE_BYTES = 4_194_304;
    private final ChatCompletionClient client;

    public JdVisualLocator(ChatCompletionClient client) {
        this.client = client;
    }

    public Point locate(Frame frame, String target, Set<String> allowed,
            Set<String> forbidden, double minConfidence) throws IOException {
        List<VisualCandidate> candidates = candidates(frame, target, minConfidence);
        for (VisualCandidate candidate : candidates) {
            Log.i("AgentNextRuntime", "vision candidate target=" + target
                    + " label=" + candidate.label() + " x=" + candidate.x()
                    + " y=" + candidate.y() + " conf=" + candidate.confidence());
        }
        Optional<VisualCandidate> chosen = VisualCandidateFilter.select(
                candidates, allowed, forbidden, minConfidence);
        if (chosen.isEmpty()) {
            throw new IOException("vision locator found no safe candidate");
        }
        VisualCandidate value = chosen.get();
        int px = (int) Math.round(value.x() * frame.width());
        int py = (int) Math.round(value.y() * frame.height());
        return new Point(Math.max(0, Math.min(frame.width() - 1, px)),
                Math.max(0, Math.min(frame.height() - 1, py)));
    }

    public List<VisualCandidate> candidates(Frame frame, String target,
            double minConfidence) throws IOException {
        if (frame == null || frame.png().length == 0 || frame.png().length > MAX_IMAGE_BYTES) {
            throw new IOException("invalid frame");
        }
        String prompt = "你在看一张京东 App 截图。只做视觉定位，不要执行任何操作。\n"
                + "目标：" + target + "\n"
                + "返回 JSON：{\"candidates\":[{\"label\":\"简短中文标签\","
                + "\"x\":0.0,\"y\":0.0,\"confidence\":0.0}]}。\n"
                + "x/y 为相对截图宽高的 0..1 归一化中心点；confidence 为 0..1。\n"
                + "最多 8 个候选；每个候选必须说明 label。\n"
                + "不要选择广告、筛选栏、地址栏、立即购买/结算按钮；"
                + "搜索提交按钮不要和拍照/大图/显示模式混淆。\n"
                + "如果目标不存在，返回 {\"candidates\":[]}。";
        String raw = stripFences(client.completeWithImage(prompt, frame.png(), 4096));
        Map<String, Object> root = Json.object(raw);
        Object list = root.get("candidates");
        List<VisualCandidate> result = new ArrayList<>();
        if (list instanceof List<?> values) {
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> map)) {
                    continue;
                }
                try {
                    String label = string(map, "label");
                    double x = number(map, "x");
                    double y = number(map, "y");
                    double confidence = number(map, "confidence");
                    if (confidence >= minConfidence) {
                        result.add(new VisualCandidate(label, x, y, confidence));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Reject malformed candidate; the rest remain usable.
                }
            }
        }
        return result;
    }

    private static String string(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return text;
    }

    private static double number(Map<?, ?> map, String key) {
        Object value = map.get(key);
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

    public java.util.List<Point> points(Frame frame, String target,
            Set<String> allowed, Set<String> forbidden, double minConfidence)
            throws IOException {
        java.util.List<Point> result = new java.util.ArrayList<>();
        for (VisualCandidate candidate : candidates(frame, target, minConfidence)) {
            if (candidate.confidence() < minConfidence) {
                continue;
            }
            String label = candidate.label().toLowerCase();
            boolean bad = forbidden.stream().anyMatch(label::contains);
            boolean good = allowed.isEmpty()
                    || allowed.stream().anyMatch(label::contains);
            if (bad || !good) {
                continue;
            }
            int px = (int) Math.round(candidate.x() * frame.width());
            int py = (int) Math.round(candidate.y() * frame.height());
            result.add(new Point(Math.max(0, Math.min(frame.width() - 1, px)),
                    Math.max(0, Math.min(frame.height() - 1, py))));
        }
        return result;
    }
}
