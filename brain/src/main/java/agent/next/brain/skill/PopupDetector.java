package agent.next.brain.skill;

import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.UiNode;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Declarative popup detector derived from open-source UI-watcher approaches:
 * uiautomator2 Watcher/WatchContext (paired popup conditions, built-in popup
 * texts), Android UiAutomator UiWatcher, and the Appium UIWatchers plugin
 * (referenceLocator = popup, actionLocator = dismiss control).
 *
 * <p>Only benign dismissal controls are eligible. Payment, credential,
 * verification and permission surfaces are never auto-dismissed; the outer
 * RiskGuardPolicy hands those to the user instead.</p>
 */
public final class PopupDetector {

    /** One concrete dismissal affordance found in an observation. */
    public record Dismissal(String nodeRef, String label, String signature, int x, int y) {
    }

    /** Explicit close/skip wording is safe to click whenever a visible control carries it. */
    private static final List<String> STRONG_CLOSE = List.of(
            "关闭弹窗", "关闭广告", "关闭", "跳过广告", "跳过",
            "close popup", "close ad", "dismiss", "skip ad", "skip", "close");

    /** Softer negative wording needs an explicit promo call-to-action on the same screen. */
    private static final List<String> WEAK_CLOSE = List.of(
            "我知道了", "知道了", "以后再说", "暂不", "暂不需要", "下次再说",
            "残忍拒绝", "放弃", "取消", "not now", "later");

    /** Strong promo CTA that justifies pairing with a weak negative control. */
    private static final List<String> PROMO_CTA = List.of(
            "立即下载", "立即更新", "立即安装", "立即领取", "立即开通", "立即预约",
            "立即体验", "去下载", "去安装", "下载", "升级", "领取", "开通");

    /** Never auto-dismiss these surfaces; they belong to the human-handoff path. */
    private static final List<String> GUARDED = List.of(
            "支付", "付款", "提交订单", "立即支付", "金额", "验证码", "密码",
            "登录", "授权", "绑定", "安全验证", "人机验证", "滑块验证", "身份验证",
            "captcha", "verify you are human");

    private static final int MAX_LABEL_LENGTH = 24;

    private PopupDetector() {
    }

    public static Optional<Dismissal> find(Observation observation) {
        List<UiNode> nodes = observation.nodes();
        StringBuilder all = new StringBuilder();
        for (UiNode node : nodes) {
            all.append(node.text()).append(' ').append(node.contentDescription()).append(' ');
        }
        String screen = all.toString().toLowerCase(Locale.ROOT);
        if (containsAny(screen, GUARDED)) {
            return Optional.empty();
        }
        boolean promo = containsAny(screen, PROMO_CTA);
        UiNode best = null;
        String bestLabel = null;
        int bestScore = 0;
        long bestArea = Long.MAX_VALUE;
        for (UiNode node : nodes) {
            if (!node.flags().contains(NodeFlag.CLICKABLE)
                    || !node.flags().contains(NodeFlag.ENABLED)
                    || !node.flags().contains(NodeFlag.VISIBLE_TO_USER)) {
                continue;
            }
            String label = (node.text() + " " + node.contentDescription()).trim();
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                continue;
            }
            long area = (long) node.bounds().width() * node.bounds().height();
            if (area <= 0) {
                continue;
            }
            String lower = label.toLowerCase(Locale.ROOT);
            int score = containsAny(lower, STRONG_CLOSE) ? 30
                    : promo && containsAny(lower, WEAK_CLOSE) ? 20 : 0;
            if (score == 0) {
                continue;
            }
            if (score > bestScore || score == bestScore && area < bestArea) {
                best = node;
                bestLabel = label;
                bestScore = score;
                bestArea = area;
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        String signature = best.viewId() + "|" + best.windowId() + "|"
                + best.className() + "|" + bestLabel.toLowerCase(Locale.ROOT);
        return Optional.of(new Dismissal(best.ref(), bestLabel, signature,
                best.bounds().centerX(), best.bounds().centerY()));
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
