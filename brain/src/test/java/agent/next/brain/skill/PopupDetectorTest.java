package agent.next.brain.skill;

import agent.next.device.NodeBounds;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.ObservationRef;
import agent.next.device.ObservationStatus;
import agent.next.device.UiNode;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static agent.next.brain.TestSupport.check;

/** Host tests for the open-source-style popup watcher/detector policy. */
public final class PopupDetectorTest {

    private PopupDetectorTest() {
    }

    public static void run() {
        dismissesPromoDialogByCloseButton();
        prefersStrongCloseOverWeakCancel();
        ignoresPositiveOnlyDialogs();
        neverDismissesPaymentOrVerification();
        weakNegativeNeedsPromoCallToAction();
        ordinaryVerificationWordingDoesNotBlockPopup();
        ignoresBlankAndLargeContainers();
        System.out.println("PopupDetectorTest: PASS (popup detection, safety guards)");
    }

    private static void dismissesPromoDialogByCloseButton() {
        Observation observation = observation(
                node(0, "限时福利", "", "android.widget.TextView", false, 10, 10, 700, 120),
                node(1, "立即领取新人礼包", "", "android.widget.TextView", false, 10, 140, 700, 220),
                node(2, "", "关闭", "android.widget.ImageView", true, 620, 20, 700, 100));
        PopupDetector.Dismissal dismissal = PopupDetector.find(observation).orElseThrow();
        check("关闭".equals(dismissal.label()), "clicks the close affordance");
        check(dismissal.x() == 660 && dismissal.y() == 60, "taps the close button center");
    }

    private static void prefersStrongCloseOverWeakCancel() {
        Observation observation = observation(
                node(0, "开启推送", "", "android.widget.TextView", false, 10, 10, 700, 120),
                node(1, "立即开通", "", "android.widget.Button", true, 10, 140, 300, 220),
                node(2, "取消", "", "android.widget.Button", true, 10, 240, 300, 320),
                node(3, "", "关闭", "android.widget.ImageView", true, 620, 20, 700, 100));
        check("关闭".equals(PopupDetector.find(observation).orElseThrow().label()),
                "strong close outranks weak negative");
    }

    private static void ignoresPositiveOnlyDialogs() {
        Observation observation = observation(
                node(0, "允许发送通知？", "", "android.widget.TextView", false, 10, 10, 700, 120),
                node(1, "允许", "", "android.widget.Button", true, 10, 140, 300, 220),
                node(2, "同意", "", "android.widget.Button", true, 10, 240, 300, 320));
        check(PopupDetector.find(observation).isEmpty(),
                "never auto-approves permission/consent dialogs");
    }

    private static void neverDismissesPaymentOrVerification() {
        Observation payment = observation(
                node(0, "确认订单 应付金额 ￥299", "", "android.widget.TextView", false, 10, 10, 700, 120),
                node(1, "", "关闭", "android.widget.ImageView", true, 620, 20, 700, 100));
        check(PopupDetector.find(payment).isEmpty(),
                "payment surfaces belong to the human handoff path");
        Observation verification = observation(
                node(0, "安全验证：请确认你是真人", "", "android.widget.TextView", false, 10, 10, 700, 120),
                node(1, "跳过", "", "android.widget.Button", true, 10, 140, 300, 220));
        check(PopupDetector.find(verification).isEmpty(),
                "verification surfaces belong to the human handoff path");
    }

    private static void weakNegativeNeedsPromoCallToAction() {
        Observation searchPage = observation(
                node(0, "热门搜索：会员 红包", "", "android.widget.TextView", false, 10, 10, 700, 120),
                node(1, "取消", "", "android.widget.Button", true, 10, 140, 300, 220));
        check(PopupDetector.find(searchPage).isEmpty(),
                "a bare cancel control is not treated as a popup");
        Observation downloadAd = observation(
                node(0, "立即下载体验新版", "", "android.widget.TextView", false, 10, 10, 700, 120),
                node(1, "取消", "", "android.widget.Button", true, 10, 140, 300, 220));
        check("取消".equals(PopupDetector.find(downloadAd).orElseThrow().label()),
                "paired promo CTA + negative control is dismissible");
    }

    private static void ordinaryVerificationWordingDoesNotBlockPopup() {
        Observation observation = observation(
                node(0, "这是页面正文，用于验证弹窗关闭后 Agent 仍能继续", "",
                        "android.widget.TextView", false, 10, 10, 700, 120),
                node(1, "限时福利 立即领取新人礼包", "", "android.widget.TextView", false, 10, 130, 700, 200),
                node(2, "关闭", "", "android.widget.Button", true, 10, 210, 300, 290));
        check("关闭".equals(PopupDetector.find(observation).orElseThrow().label()),
                "ordinary text containing 验证 must not be treated as a verification surface");
    }

    private static void ignoresBlankAndLargeContainers() {
        check(PopupDetector.find(observation()).isEmpty(), "blank observation has no popup");
        Observation container = observation(
                node(0, "立即领取 " + "很长很长的活动说明文字", "", "android.widget.FrameLayout", true,
                        0, 0, 720, 800),
                node(1, "", "关闭", "android.widget.ImageView", true, 620, 20, 700, 100));
        check(PopupDetector.find(container).isPresent(),
                "small close node is picked even with a large container present");
    }

    private static Observation observation(UiNode... nodes) {
        return new Observation(ObservationStatus.READY, new ObservationRef("observation-1", 1),
                "com.example.shop", "ShopActivity", 0, 7, true, Instant.now(), 1, false,
                new NodeBounds(0, 0, 720, 1280), List.of(nodes));
    }

    private static UiNode node(int index, String text, String description, String className,
            boolean clickable, int left, int top, int right, int bottom) {
        Set<NodeFlag> flags = EnumSet.of(NodeFlag.ENABLED, NodeFlag.VISIBLE_TO_USER);
        if (clickable) {
            flags.add(NodeFlag.CLICKABLE);
        }
        return new UiNode("ref-" + index, index, -1, 7, "", className, text, description,
                new NodeBounds(left, top, right, bottom), flags, false);
    }
}
