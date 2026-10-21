package agent.next.app;

import agent.next.brain.action.VisualTapResolution;
import agent.next.brain.action.VisualTapResolver;
import agent.next.brain.model.ChatCompletionClient;
import agent.next.brain.model.Json;
import agent.next.device.DeviceException;
import agent.next.device.Frame;
import agent.next.device.Point;
import agent.next.device.TargetHandle;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Locates a requested visible control from a fresh target-display screenshot. */
final class ReactiveVisualTapLocator implements VisualTapResolver {
    private static final int MAX_IMAGE_BYTES = 4_194_304;
    private static final double MIN_CONFIDENCE = 0.8;
    private static final Set<String> FORBIDDEN = Set.of(
            "支付", "付款", "确认支付", "提交订单", "下单", "删除", "发送", "发布",
            "密码", "验证码", "登录", "授权", "指纹", "人脸", "立即购买", "立即下单");
    private final ChatCompletionClient client;

    ReactiveVisualTapLocator(ChatCompletionClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public VisualTapResolution locate(TargetHandle target, String description, Frame frame)
            throws DeviceException {
        Objects.requireNonNull(target, "target");
        if (frame == null || frame.png().length == 0 || frame.png().length > MAX_IMAGE_BYTES) {
            throw new DeviceException(DeviceException.Code.REJECTED, "visual tap frame is unavailable");
        }
        String prompt = "你是手机界面的视觉定位器，只定位，不操作。\n"
                + "目标控件：" + description + "\n"
                + "仅当截图中能明确看到该目标时返回："
                + "{\"label\":\"目标可见文字\",\"x\":0.0,\"y\":0.0,\"confidence\":0.0}。\n"
                + "x/y 是截图宽高归一化后的目标中心点。label 必须抄录目标本身可见的文字，"
                + "不得用目标描述代替屏幕证据；只返回一个候选。找不到或有歧义时返回空 label 且 confidence 为 0。\n"
                + "根据目标描述的含义、位置和控件形态定位；目标描述可能包含上下文，不要求 label 与描述逐字相同。"
                + "定位搜索输入框时，点框内的文字或占位提示区域，不要点右侧搜索按钮或放大镜提交图标。\n"
                + "可定位加入购物车和规格确认控件；不得定位支付、付款、下单、提交订单、发送、删除、登录、密码、验证码、授权或立即购买控件；"
                + "不要选择广告或邻近控件。只输出 JSON。";
        try {
            Map<String, Object> value = Json.object(client.completeWithImage(prompt, frame.png(), 512));
            if (!value.keySet().equals(Set.of("label", "x", "y", "confidence"))) {
                throw rejected("visual locator returned unexpected fields");
            }
            String label = Json.string(value, "label").trim();
            double x = number(value, "x"), y = number(value, "y"), confidence = number(value, "confidence");
            String lower = label.toLowerCase(java.util.Locale.ROOT);
            if (label.isBlank()
                    || FORBIDDEN.stream().anyMatch(lower::contains)
                    || x < 0 || x > 1 || y < 0 || y > 1 || confidence < MIN_CONFIDENCE) {
                throw rejected("visual target was absent, unsafe, or ambiguous");
            }
            Point point = new Point(Math.min(frame.width() - 1, (int) Math.round(x * frame.width())),
                    Math.min(frame.height() - 1, (int) Math.round(y * frame.height())));
            return new VisualTapResolution(point, label, confidence);
        } catch (IOException | IllegalArgumentException failure) {
            if (failure instanceof VisualTapRejectedException rejected) {
                throw rejected.deviceException;
            }
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "visual target could not be confirmed", failure);
        }
    }

    private static double number(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof BigDecimal number)) throw new IllegalArgumentException("invalid " + key);
        return number.doubleValue();
    }

    private static VisualTapRejectedException rejected(String message) {
        return new VisualTapRejectedException(new DeviceException(DeviceException.Code.REJECTED, message));
    }

    private static final class VisualTapRejectedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final DeviceException deviceException;
        private VisualTapRejectedException(DeviceException deviceException) {
            super(deviceException.getMessage());
            this.deviceException = deviceException;
        }
    }
}
