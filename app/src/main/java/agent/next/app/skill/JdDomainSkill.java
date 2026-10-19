package agent.next.app.skill;

import android.util.Log;

import agent.next.brain.Subgoal;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.LoopPolicy;
import agent.next.brain.session.SubgoalVerifier;
import agent.next.device.Frame;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.ObservationOptions;
import agent.next.device.Point;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;
import agent.phone.capability.BlockingDeviceApi;

import java.util.Optional;
import java.util.Set;

/** JD app profile: textless bottom tabs, search, result cards, detail add and cart. */
public final class JdDomainSkill implements DomainSkill {

    private static final String PACKAGE = "com.jingdong.app.mall";
    private static final String TAG = "AgentNextRuntime";
    private final JdVisualLocator locator;

    public JdDomainSkill(JdVisualLocator locator) {
        this.locator = locator;
    }

    @Override
    public boolean matches(String packageName, TaskSpec task) {
        return PACKAGE.equals(packageName);
    }

    @Override
    public void prepareTarget(String packageName, TargetHandle target, TaskSpec task,
            BlockingDeviceApi api) {
        prepareJdHome(api, target);
    }

    @Override
    public Optional<LoopPolicy> policy(SkillContext context) {
        if (!PACKAGE.equals(context.target().packageName())) {
            return Optional.empty();
        }
        Log.i(TAG, "JD policy candidate " + context.subgoal().id()
                + " verifier=" + context.subgoal().verifierId()
                + " intent=" + context.subgoal().intent());
        Subgoal subgoal = context.subgoal();
        if (subgoal.intent().contains("输入") || subgoal.intent().contains("提交")) {
            prepareJdSearchEntry(context.api(), context.target());
        }
        if (subgoal.intent().contains("第一款")
                || (subgoal.intent().contains("结果")
                && subgoal.intent().contains("详情"))) {
            try {
                Frame frame = context.api().observations().captureFrame(context.target());
                java.util.List<Point> results = locator.points(frame,
                        JdAppProfile.PROFILE.resultCardPrompt(),
                        JdAppProfile.PROFILE.resultCardLabels(),
                        JdAppProfile.PROFILE.resultCardForbidden(),
                        0.5);
                Log.i(TAG, "JD result-card vision points=" + results);
                return Optional.of(new RiskGuardPolicy(
                        new ResultCardPolicy(results, JdAppProfile.PROFILE.detailMarkers()),
                        context.handoff()));
            } catch (Throwable failure) {
                Log.w(TAG, "JD result locator unavailable: " + failure);
            }
        }
        if (context.task().goal().contains("搜索")
                && !subgoal.intent().contains("打开")
                && !subgoal.intent().contains("启动")
                && (subgoal.intent().contains("搜索")
                || subgoal.intent().contains("输入"))) {
            prepareJdSearchEntry(context.api(), context.target());
            Point entry = null;
            try {
                Frame frame = context.api().observations().captureFrame(context.target());
                entry = locator.locate(frame, JdAppProfile.PROFILE.searchEntryPrompt(),
                        JdAppProfile.PROFILE.searchEntryLabels(),
                        JdAppProfile.PROFILE.searchEntryForbidden(), 0.5);
            } catch (Throwable failure) {
                Log.w(TAG, "JD search entry locator unavailable: " + failure);
            }
            Log.i(TAG, "JD search policy selected: " + subgoal.id());
            return Optional.of(new RiskGuardPolicy(new SearchPolicy(entry, locator, JdAppProfile.PROFILE),
                    context.handoff()));
        }
        if (subgoal.intent().contains("加入购物车")) {
            Log.i(TAG, "JD add-to-cart policy selected: " + subgoal.id());
            return Optional.of(new RiskGuardPolicy(new AddToCartPolicy(JdAppProfile.PROFILE, locator), context.handoff()));
        }
        if ("jd.cart".equals(subgoal.verifierId())
                || subgoal.intent().contains("购物车")) {
            Log.i(TAG, "JD cart policy selected: " + subgoal.id()
                    + " verifier=" + subgoal.verifierId());
            prepareJdCart(context.api(), context.target());
            return Optional.of(new RiskGuardPolicy(new CartWaitPolicy(), context.handoff()));
        }
        return Optional.of(new RiskGuardPolicy(context.promptFallback(), context.handoff()));
    }

    @Override
    public String verifierId() {
        return "jd.cart";
    }

    @Override
    public SubgoalVerifier verifier(SubgoalVerifier fallback) {
        JdCartVerifier cart = new JdCartVerifier();
        return (task, subgoal, target, observation) -> {
            if (PACKAGE.equals(target.packageName())
                    && (subgoal.intent().contains("详情")
                    || subgoal.intent().contains("第一款"))) {
                for (UiNode node : observation.nodes()) {
                    if (node.viewId().contains("productdetail")) {
                        Log.i(TAG, "verify detail " + subgoal.id() + " -> true by view id");
                        return new SubgoalVerifier.Verification(
                                true, "JD product detail view visible");
                    }
                }
            }
            if (PACKAGE.equals(target.packageName()) && task.goal().contains("购物车")) {
                SubgoalVerifier.Verification result = cart.verify(
                        task, subgoal, target, observation);
                if (result.satisfied()) {
                    Log.i(TAG, "verify cart " + subgoal.id() + " -> true");
                    return result;
                }
            }
            return fallback.verify(task, subgoal, target, observation);
        };
    }

    private void prepareJdHome(BlockingDeviceApi api, TargetHandle target) {
        try {
            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            for (int attempt = 0; attempt < 3; attempt++) {
                boolean babel = observation.activityName().contains("BabelActivity");
                StringBuilder texts = new StringBuilder();
                for (UiNode node : observation.nodes()) {
                    texts.append(node.text()).append(' ').append(node.contentDescription());
                }
                if (!babel && !texts.toString().contains("11.11主会场")
                        && !texts.toString().contains("J_babelOptPage")
                        && !texts.toString().contains("挑好物逛京东")) {
                    break;
                }
                Log.i(TAG, "JD event page detected, back");
                api.input().back(target, observation);
                Thread.sleep(900L);
                observation = api.observations().observe(
                        target, ObservationOptions.defaults());
            }
            UiNode home = null;
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.CLICKABLE)
                        && (node.text().contains("首页")
                        || node.contentDescription().contains("首页"))) {
                    home = node;
                    break;
                }
            }
            if (home != null) {
                api.input().tap(target, observation,
                        new Point(home.bounds().centerX(), home.bounds().centerY()));
                Thread.sleep(800L);
                observation = api.observations().observe(
                        target, ObservationOptions.defaults());
            }
            Log.i(TAG, "JD home prepared act=" + observation.activityName()
                    + " nodes=" + observation.nodes().size());
        } catch (Throwable failure) {
            Log.w(TAG, "JD home prelude failed: " + failure);
        }
    }

    private void prepareJdSearchEntry(BlockingDeviceApi api, TargetHandle target) {
        try {
            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.EDITABLE) && !node.hasFlag(NodeFlag.PASSWORD)) {
                    return;
                }
            }
            UiNode entry = null;
            for (UiNode node : observation.nodes()) {
                if (node.hasFlag(NodeFlag.CLICKABLE)
                        && (node.text().contains("搜索")
                        || node.contentDescription().contains("搜索"))) {
                    entry = node;
                    break;
                }
            }
            if (entry == null) {
                Log.i(TAG, "JD search entry not in tree; leaving to visual locator");
                return;
            }
            api.input().tap(target, observation,
                    new Point(entry.bounds().centerX(), entry.bounds().centerY()));
            Thread.sleep(700L);
            api.observations().observe(target, ObservationOptions.defaults());
            Log.i(TAG, "JD search entry opened");
        } catch (Throwable failure) {
            Log.w(TAG, "JD search entry prelude failed: " + failure);
        }
    }

    private void prepareJdCart(BlockingDeviceApi api, TargetHandle target) {
        try {
            Observation observation = api.observations().observe(
                    target, ObservationOptions.defaults());
            for (UiNode node : observation.nodes()) {
                String popup = node.text() + " " + node.contentDescription();
                if (popup.contains("别折腾了") || popup.contains("跳过广告")) {
                    api.input().back(target, observation);
                    Thread.sleep(1000L);
                    observation = api.observations().observe(
                            target, ObservationOptions.defaults());
                    break;
                }
            }
            boolean title = false;
            boolean footer = false;
            UiNode cartTab = null;
            for (UiNode node : observation.nodes()) {
                if (node.viewId().contains("cart.feature")) {
                    Log.i(TAG, "JD cart view already visible");
                    return;
                }
                String text = node.text() + " " + node.contentDescription();
                if (text.contains("购物车")) {
                    title = true;
                    if (node.hasFlag(NodeFlag.CLICKABLE)) {
                        cartTab = node;
                    }
                }
                if (text.contains("合计") || text.contains("去结算")) {
                    footer = true;
                }
            }
            if (title && footer) {
                return;
            }
            if (cartTab == null) {
                try {
                    Frame frame = api.observations().captureFrame(target);
                    java.util.List<agent.next.brain.grounding.VisualCandidate> candidates =
                            locator.candidates(frame,
                                    "京东底部导航栏的购物车 tab（不要结算/购买/广告）", 0.4);
                    java.util.List<Point> points = new java.util.ArrayList<>();
                    for (agent.next.brain.grounding.VisualCandidate candidate : candidates) {
                        String label = candidate.label().toLowerCase();
                        Log.i(TAG, "JD cart candidate label=" + candidate.label()
                                + " x=" + candidate.x() + " y=" + candidate.y()
                                + " conf=" + candidate.confidence());
                        boolean forbidden = JdAppProfile.PROFILE.cartForbidden().stream()
                                .anyMatch(label::contains);
                        boolean cartLabel = JdAppProfile.PROFILE.cartLabels().stream()
                                .anyMatch(label::contains);
                        if (!forbidden && cartLabel && candidate.y() > 0.6) {
                            int px = (int) Math.round(candidate.x() * frame.width());
                            int py = (int) Math.round(candidate.y() * frame.height());
                            points.add(new Point(px, py));
                        }
                    }
                    if (points.isEmpty()) {
                        points = locator.points(frame,
                                "京东底部导航栏的购物车 tab（不要结算/购买/广告）",
                                JdAppProfile.PROFILE.cartLabels(),
                                JdAppProfile.PROFILE.cartForbidden(), 0.5);
                    }
                    for (Point point : points) {
                        api.input().tap(target, observation, point);
                        Thread.sleep(1200L);
                        if (awaitCartPage(api, target)) {
                            Log.i(TAG, "JD cart tab selected by vision candidate");
                            return;
                        }
                    }
                    Log.i(TAG, "JD cart tab candidates did not open cart");
                } catch (Throwable failure) {
                    Log.i(TAG, "JD cart tab visual locator unavailable");
                }
                return;
            }
            api.input().tap(target, observation,
                    new Point(cartTab.bounds().centerX(), cartTab.bounds().centerY()));
            Thread.sleep(1500L);
            Observation cart = api.observations().observe(
                    target, ObservationOptions.defaults());
            StringBuilder sample = new StringBuilder();
            for (UiNode node : cart.nodes()) {
                String value = node.text().isBlank()
                        ? node.contentDescription() : node.text();
                if (!value.isBlank() && sample.length() < 220) {
                    sample.append(value).append(" | ");
                }
            }
            Log.i(TAG, "JD cart after tap act=" + cart.activityName()
                    + " nodes=" + cart.nodes().size() + " text=" + sample);
        } catch (Throwable failure) {
            Log.w(TAG, "JD cart prelude failed: " + failure);
        }
    }

    private boolean awaitCartPage(BlockingDeviceApi api, TargetHandle target)
            throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            Observation cart = api.observations().observe(
                    target, ObservationOptions.defaults());
            for (UiNode node : cart.nodes()) {
                if (node.viewId().contains("cart.feature")
                        || node.text().contains("合计")
                        || node.text().contains("去结算")
                        || node.contentDescription().contains("购物车")) {
                    return true;
                }
            }
            Thread.sleep(700L);
        }
        return false;
    }
}
