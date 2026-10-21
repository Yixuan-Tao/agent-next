package agent.next.app.skill;

import agent.next.brain.model.StageData;
import agent.next.brain.model.VisualVerification;
import agent.next.brain.model.VisualVerificationRequest;
import agent.next.brain.model.VisualVerifier;
import agent.next.device.DeviceException;
import agent.next.device.Frame;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Final-page and extracted-title evidence from the same browser frame. */
public final class BrowserResultVerifier {
    private final String originalGoal;
    private final VisualVerifier verifier;
    private boolean satisfied;
    private String title = "";
    private String evidence = "尚未核验最终页面";

    public BrowserResultVerifier(String originalGoal, VisualVerifier verifier) {
        this.originalGoal = Objects.requireNonNull(originalGoal, "originalGoal");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    public void invalidate(String reason) {
        satisfied = false;
        title = "";
        evidence = reason;
    }

    public void check(Frame frame, StageData data) throws DeviceException {
        invalidate("最终页面证据未知");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(data, "data");
        String candidateTitle = data.entities().getOrDefault("title", "").trim();
        if (candidateTitle.isEmpty() || candidateTitle.length() > 120
                || candidateTitle.indexOf('\n') >= 0 || candidateTitle.indexOf('\r') >= 0) {
            invalidate("未提取到可核验的实际页面标题");
            return;
        }
        VisualVerificationRequest request = new VisualVerificationRequest(
                "用户原始要求：" + originalGoal + "\n最后一项完成条件是从同一截图提取的页面标题，"
                        + "必须与截图中标题位置的文字逐字一致；正文中的普通提及不算标题。",
                List.of("当前页面正文与用户原始要求的主题一致。",
                        "当前已打开可阅读的网页或图文正文，不是首页、搜索建议、搜索结果列表或只有播放器的视频页。",
                        "实际页面标题和正文均可见且可读。", candidateTitle), frame);
        try {
            VisualVerification result = Objects.requireNonNull(verifier.verify(request),
                    "final verification");
            evidence = "matched=" + result.matched().size() + ", missing="
                    + result.missing().size() + ", frame=" + frame.sequence();
            satisfied = result.verified() && result.missing().isEmpty()
                    && result.matched().containsAll(request.conditions());
            if (satisfied) {
                title = candidateTitle;
            }
        } catch (IOException failure) {
            throw new DeviceException(DeviceException.Code.INTERNAL,
                    "final browser verification failed", failure);
        }
    }

    public boolean satisfied() {
        return satisfied;
    }

    public String title() {
        return title;
    }

    public String evidence() {
        return evidence;
    }
}
