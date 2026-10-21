package agent.next.brain.action;

import agent.next.brain.ActionDescriptor;
import agent.next.brain.ActionFlag;
import agent.next.brain.ActionKind;
import agent.next.brain.RiskClass;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Raises risk from the actual semantic target text. A model may propose LOW,
 * but payment/send/delete/credential wording can only increase the risk.
 *
 * <p>The classifier deliberately errs toward raising: a false positive causes
 * a confirmation, a false negative would execute an irreversible action.</p>
 */
public final class GuiRiskClassifier {

    private static final Set<String> PAYMENT = Set.of(
            "支付", "付款", "立即支付", "确认支付", "下单", "提交订单",
            "转账", "充值", "订购", "订阅", "pay", "place order", "subscribe");
    /** Navigating into checkout is reversible; only the actual commitment stays a payment action. */
    private static final Set<String> PAYMENT_NAVIGATION = Set.of(
            "去结算", "结算", "去购买", "立即购买", "购买", "checkout", "buy", "purchase");
    private static final Set<String> MESSAGE = Set.of(
            "发送", "发表", "评论", "回复", "投稿", "提交评价", "send", "post",
            "reply", "message", "email");
    private static final Set<String> DESTRUCTIVE = Set.of(
            "删除", "卸载", "清空", "注销", "delete", "remove", "uninstall", "clear");
    private static final Set<String> INSTALL = Set.of(
            "安装", "install", "授权", "permission");
    private static final Set<String> CREDENTIAL = Set.of(
            "密码", "验证码", "otp", "password", "passkey", "指纹", "人脸", "credential");

    private GuiRiskClassifier() {
    }

    public enum Category {
        PAYMENT, MESSAGE, DESTRUCTIVE, INSTALL, CREDENTIAL, NONE
    }

    public static Category category(ActionDescriptor action) {
        String text = semanticText(action);
        if (containsAny(text, CREDENTIAL)) {
            return Category.CREDENTIAL;
        }
        if (containsAny(text, PAYMENT)) {
            return Category.PAYMENT;
        }
        if (containsAny(text, PAYMENT_NAVIGATION)) {
            return Category.NONE;
        }
        if (containsAny(text, MESSAGE)) {
            return Category.MESSAGE;
        }
        if (containsAny(text, DESTRUCTIVE)) {
            return Category.DESTRUCTIVE;
        }
        if (containsAny(text, INSTALL)) {
            return Category.INSTALL;
        }
        return Category.NONE;
    }

    /** Returns a descriptor whose kind/risk/flags are at least as strict as the input. */
    public static ActionDescriptor raise(ActionDescriptor action) {
        String text = semanticText(action);
        ActionKind kind = action.kind();
        RiskClass risk = action.risk();
        Set<ActionFlag> flags = action.flags().isEmpty()
                ? EnumSet.noneOf(ActionFlag.class) : EnumSet.copyOf(action.flags());

        if (containsAny(text, CREDENTIAL)) {
            risk = stronger(risk, RiskClass.CREDENTIAL);
            kind = ActionKind.COMMITMENT;
            flags.add(ActionFlag.CREDENTIAL);
        }
        if (containsAny(text, PAYMENT)) {
            kind = ActionKind.COMMITMENT;
            risk = stronger(risk, RiskClass.COMMITMENT);
        }
        if (containsAny(text, MESSAGE)) {
            kind = ActionKind.COMMITMENT;
            risk = stronger(risk, RiskClass.COMMITMENT);
            flags.add(ActionFlag.EXTERNAL_MESSAGE);
        }
        if (containsAny(text, DESTRUCTIVE)) {
            kind = ActionKind.COMMITMENT;
            risk = stronger(risk, RiskClass.COMMITMENT);
            flags.add(ActionFlag.DESTRUCTIVE);
        }
        if (containsAny(text, INSTALL)) {
            kind = ActionKind.COMMITMENT;
            risk = stronger(risk, RiskClass.COMMITMENT);
        }
        return new ActionDescriptor(action.id(), action.description(), kind, risk,
                action.targetApp(), action.payloadHash(), flags);
    }

    public static boolean isCommitmentLike(ActionDescriptor action) {
        ActionDescriptor raised = raise(action);
        return raised.kind() == ActionKind.COMMITMENT
                || raised.risk() == RiskClass.COMMITMENT
                || raised.risk() == RiskClass.LEGAL_MEDICAL
                || raised.risk() == RiskClass.CREDENTIAL
                || raised.flags().contains(ActionFlag.EXTERNAL_MESSAGE)
                || raised.flags().contains(ActionFlag.DESTRUCTIVE)
                || raised.flags().contains(ActionFlag.CREDENTIAL);
    }

    private static boolean containsAny(String text, Set<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    private static String semanticText(ActionDescriptor action) {
        return (action.id() + " " + action.description())
                .replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
    }

    private static RiskClass stronger(RiskClass current, RiskClass proposed) {
        return rank(proposed) > rank(current) ? proposed : current;
    }

    private static int rank(RiskClass risk) {
        return switch (risk) {
            case LOW -> 0;
            case REVERSIBLE_SIDE_EFFECT -> 1;
            case COMMITMENT -> 2;
            case LEGAL_MEDICAL -> 3;
            case CREDENTIAL -> 4;
        };
    }
}
