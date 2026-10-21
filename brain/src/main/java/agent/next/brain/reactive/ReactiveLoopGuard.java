package agent.next.brain.reactive;

import agent.next.brain.Budget;
import agent.next.brain.action.ActionProposal;
import agent.next.brain.loop.ObservationFingerprint;
import agent.next.device.Observation;
import agent.next.device.UiNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Task-local route repetition and consecutive lack of progress, independent of model prose. */
final class ReactiveLoopGuard {
    private final Budget budget;
    private final Set<String> seen = new HashSet<>();
    private final Map<String, Integer> routes = new HashMap<>();
    private String previousExact, previousPage;
    private int noEffect, noProgress;
    private boolean recoveryUsed;

    ReactiveLoopGuard(Budget budget) { this.budget = budget; }

    String observe(Observation observation, boolean afterAction) {
        String exact = ObservationFingerprint.of(observation).contentDigest();
        String page = page(observation);
        boolean known = !seen.add(page);
        String feedback = "";
        if (afterAction) {
            noEffect = exact.equals(previousExact) ? noEffect + 1 : 0;
            noProgress = known ? noProgress + 1 : 0;
            if (!known) recoveryUsed = false;
            if (known && !page.equals(previousPage)) {
                feedback = "检测到页面往返：返回已访问页面，检查已完成步骤，避免重走同一路径或重复写入。";
            }
            if (pressured()) feedback += "连续无效果=" + noEffect + "，连续无进展=" + noProgress
                    + "；已达到恢复阈值，只允许一次调整方法的恢复动作，仍无进展则停止。";
        }
        previousExact = exact;
        previousPage = page;
        return feedback;
    }

    String block(Observation observation, ActionProposal proposal) {
        return blockRoute(proposal instanceof ActionProposal.Wait ? "" : route(observation, proposal));
    }

    String blockOpen(Observation observation, String app) { return blockRoute(page(observation) + ":OpenApp:" + app); }

    void opened(Observation observation, String app) { routes.merge(page(observation) + ":OpenApp:" + app, 1, Integer::sum); }

    private String blockRoute(String route) {
        if (!route.isEmpty() && routes.getOrDefault(route, 0) >= 2) {
            return "页面往返路径已执行两次，阻止重复动作；选择其他真实入口或核对已完成结果。";
        }
        if (pressured()) {
            if (recoveryUsed) return "连续无效果或无进展，恢复动作仍未推进任务，停止继续绕行。";
            recoveryUsed = true;
        }
        return "";
    }

    void executed(Observation observation, ActionProposal proposal) {
        routes.merge(route(observation, proposal), 1, Integer::sum);
    }

    void verifiedProgress() {
        noEffect = 0;
        noProgress = 0;
        recoveryUsed = false;
        routes.clear();
    }

    String snapshot() { return "循环状态：连续无效果=" + noEffect + "/" + budget.maxConsecutiveNoEffect()
            + "；连续无进展=" + noProgress + "/" + budget.maxConsecutiveNoProgress()
            + "；页面已访问=" + seen.size() + "；恢复动作已使用=" + recoveryUsed; }

    private boolean pressured() { return noEffect >= budget.maxConsecutiveNoEffect()
            || noProgress >= budget.maxConsecutiveNoProgress(); }

    private static String route(Observation observation, ActionProposal proposal) {
        String action = proposal.getClass().getSimpleName();
        if (proposal instanceof ActionProposal.Tap tap) {
            action += tap.selector().find(observation).map(ReactiveLoopGuard::nodeIdentity)
                    .orElse(tap.selector().description());
        } else if (proposal instanceof ActionProposal.SetText text) {
            action += text.selector().find(observation).map(ReactiveLoopGuard::nodeIdentity)
                    .orElse(text.selector().description()) + ":" + text.text();
        } else if (proposal instanceof ActionProposal.Swipe swipe) action += swipe.direction();
        // Waiting durations and model-generated ids/descriptions do not create a new route.
        return page(observation) + ":" + action;
    }

    private static String page(Observation observation) {
        List<String> fields = new ArrayList<>();
        fields.add(observation.packageName());
        fields.add(observation.activityName());
        observation.nodes().stream().map(ReactiveLoopGuard::nodeIdentity).sorted().forEach(fields::add);
        return fingerprint(fields);
    }

    private static String nodeIdentity(UiNode node) {
        return fingerprint(List.of(node.viewId(), node.className(), node.text(),
                node.contentDescription(), node.flags().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.toList()).toString()));
    }

    private static String fingerprint(List<String> fields) {
        StringBuilder material = new StringBuilder();
        fields.forEach(field -> material.append(field.length()).append(':').append(field));
        return UUID.nameUUIDFromBytes(material.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }
}
