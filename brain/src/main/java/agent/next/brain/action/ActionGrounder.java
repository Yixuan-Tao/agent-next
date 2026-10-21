package agent.next.brain.action;

import agent.next.brain.ActionDescriptor;
import agent.next.brain.SuccessCriterion;
import agent.next.brain.TaskSpec;
import agent.next.brain.loop.NodeSelector;
import agent.next.brain.skill.SkillSelector;
import agent.next.device.DeviceException;
import agent.next.device.NodeFlag;
import agent.next.device.NodeBounds;
import agent.next.device.Observation;
import agent.next.device.TargetHandle;
import agent.next.device.UiNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Resolves a semantic proposal against one ready observation. It never guesses:
 * zero matches is STALE, multiple matches is REJECTED. The result is the only
 * object the executor may act on.
 */
public final class ActionGrounder {

    public GroundedAction ground(TaskSpec task, TargetHandle target,
            Observation observation, ActionProposal proposal) throws DeviceException {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(proposal, "proposal");
        if (observation.reference().isEmpty()) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "observation is not actionable");
        }
        if (!target.packageName().equals(observation.packageName())) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "observation package does not match target");
        }
        if (!task.id().equals(target.taskId()) || !task.allowedApps().contains(target.packageName())) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "target is outside task scope");
        }
        GroundedAction.GroundedTarget grounded;
        UiNode selected = null;
        List<UiNode> webContext = List.of();
        if (proposal instanceof ActionProposal.Tap tap) {
            if (tap.selector() instanceof ObservedNodeSelector observed) {
                webContext = observed.resolve(observation, NodeFlag.CLICKABLE);
                selected = webContext.get(webContext.size() - 1);
            } else if (tap.selector() instanceof SkillSelector.WebTextContains webText) {
                selected = webText(observation, webText);
                webContext = webAncestors(observation, selected);
            } else {
                selected = node(observation, tap.selector(), NodeFlag.CLICKABLE);
            }
            // Click the observed label inside its actionable parent, rather than the parent's
            // center, which may cover an unrelated image or another nested control.
            NodeBounds tapBounds = tap.selector() instanceof ObservedNodeSelector
                    ? webContext.get(0).bounds() : selected.bounds();
            grounded = new GroundedAction.Node(selected.ref(), tapBounds);
        } else if (proposal instanceof ActionProposal.SetText setText) {
            if (setText.selector() instanceof ObservedNodeSelector observed) {
                webContext = observed.resolve(observation, NodeFlag.EDITABLE);
                selected = webContext.get(0);
            } else {
                selected = find(observation, setText.selector());
            }
            if (!selected.hasFlag(NodeFlag.EDITABLE)) {
                throw new DeviceException(DeviceException.Code.REJECTED,
                        "text target is not editable");
            }
            if (selected.hasFlag(NodeFlag.PASSWORD)) {
                throw new DeviceException(DeviceException.Code.REJECTED,
                        "text target is a password field");
            }
            grounded = new GroundedAction.Node(selected.ref(), selected.bounds());
        } else if (proposal instanceof ActionProposal.VisualTap visualTap) {
            grounded = new GroundedAction.None();
        } else {
            grounded = new GroundedAction.None();
        }
        // Only the observed node supplies target semantics; a selector is a lookup hint.
        String description = proposal.description();
        if (proposal instanceof ActionProposal.VisualTap visualTap) {
            description += "\nVisual target: " + visualTap.target();
        }
        if (selected != null) {
            description += "\nObserved target: text=" + selected.text()
                    + "; contentDescription=" + selected.contentDescription()
                    + "; viewId=" + selected.viewId();
        }
        for (UiNode context : webContext) {
            description += "\nObserved context: text=" + context.text()
                    + "; contentDescription=" + context.contentDescription();
        }
        ActionDescriptor descriptor = GuiRiskClassifier.raise(new ActionDescriptor(
                proposal.id(), description, proposal.kind(),
                proposal.proposedRisk(), target.packageName(),
                approvalHash(task, target, observation, proposal, selected, webContext),
                proposal.flags()));
        return new GroundedAction(target.taskId(), target.packageName(), target.token(),
                observation.reference().orElseThrow(), proposal, grounded, descriptor);
    }

    private static UiNode node(Observation observation, NodeSelector selector,
            NodeFlag required) throws DeviceException {
        UiNode selected = find(observation, selector);
        if (!selected.hasFlag(required)) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "selected node lacks " + required);
        }
        return selected;
    }

    private static String approvalHash(TaskSpec task, TargetHandle target,
            Observation observation, ActionProposal proposal, UiNode selected,
            List<UiNode> webContext) {
        ObservedNodeSelector observedSelector = observedSelector(proposal);
        boolean ignoreDynamicText = observedSelector != null && observedSelector.isSearchControl();
        List<String> fields = new ArrayList<>(List.of("grounded-action-v1",
                task.id(), task.goal(), task.autonomy().level().name()));
        addList(fields, task.constraints());
        fields.add(Integer.toString(task.successCriteria().size()));
        for (SuccessCriterion criterion : task.successCriteria()) {
            fields.addAll(List.of(criterion.id(), criterion.description(),
                    Boolean.toString(criterion.required())));
        }
        addList(fields, task.allowedApps().stream().sorted().collect(Collectors.toList()));
        addList(fields, task.deniedActions().stream().sorted().collect(Collectors.toList()));
        addList(fields, task.autonomy().autoAuthorizedActions().stream().sorted().collect(Collectors.toList()));
        fields.addAll(List.of(task.budget().toString(), target.token(),
                Long.toString(target.generation()), target.taskId(), target.packageName(),
                observation.activityName(), Integer.toString(observation.displayId()),
                Integer.toString(observation.windowId()), proposal.payloadHash()));
        if (selected != null) {
            addNodeIdentity(fields, selected, ignoreDynamicText);
            if (!webContext.isEmpty()) {
                boolean rebound = proposal instanceof ActionProposal.Tap tap && tap.selector() instanceof ObservedNodeSelector
                        || proposal instanceof ActionProposal.SetText text && text.selector() instanceof ObservedNodeSelector;
                fields.addAll(List.of("web-context",Integer.toString(webContext.size())));
                if (!rebound) fields.addAll(List.of(Integer.toString(selected.index()),Integer.toString(selected.parentIndex())));
                for (UiNode ancestor : webContext) {
                    if (!rebound) fields.addAll(List.of(Integer.toString(ancestor.index()),Integer.toString(ancestor.parentIndex())));
                    addNodeIdentity(fields, ancestor, ignoreDynamicText);
                }
            }
        } else {
            fields.add("none");
            fields.add(observation.windowBounds().orElseThrow().toString());
            if (proposal instanceof ActionProposal.VisualTap visualTap) {
                fields.add("visual-target");
                fields.add(visualTap.target());
            }
        }
        // observation id/sequence and node ref are deliberately excluded: every capture renews them.
        return GroundedAction.fingerprint(fields);
    }

    private static void addNodeIdentity(List<String> fields, UiNode node) {
        addNodeIdentity(fields, node, false);
    }

    private static void addNodeIdentity(List<String> fields, UiNode node, boolean ignoreDynamicText) {
        fields.add("node");
        if (ignoreDynamicText) {
            fields.addAll(List.of(node.viewId(), node.className(), Integer.toString(node.windowId())));
            return;
        }
        fields.addAll(List.of(node.text(), node.contentDescription(), node.viewId(), node.className(),
                Integer.toString(node.windowId()), node.bounds().toString(),
                Boolean.toString(node.textTruncated())));
        addList(fields, node.flags().stream().map(Enum::name).sorted().collect(Collectors.toList()));
    }

    private static ObservedNodeSelector observedSelector(ActionProposal proposal) {
        if (proposal instanceof ActionProposal.Tap tap
                && tap.selector() instanceof ObservedNodeSelector selector) return selector;
        if (proposal instanceof ActionProposal.SetText setText
                && setText.selector() instanceof ObservedNodeSelector selector) return selector;
        return null;
    }

    private static UiNode webText(Observation observation, SkillSelector.WebTextContains selector)
            throws DeviceException {
        if (!observation.stable() || observation.truncated()) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "Web text requires a stable, complete observation");
        }
        List<UiNode> matches = new ArrayList<>();
        for (UiNode candidate : observation.nodes()) {
            if (webTextEligible(observation, candidate) && candidate.text().contains(selector.value())) {
                matches.add(candidate);
            }
        }
        if (matches.isEmpty()) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "no eligible text within an observed WebView: " + selector.description());
        }
        if (matches.size() > 1) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "ambiguous Web text selector: " + selector.description());
        }
        return matches.get(0);
    }

    /** Trusted per-node affordance; selection must still resolve to exactly one eligible node. */
    public static boolean webTextEligible(Observation observation, UiNode node) {
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(node, "node");
        return observation.reference().isPresent() && observation.stable() && !observation.truncated()
                && observation.nodes().contains(node)
                && node.hasFlag(NodeFlag.VISIBLE_TO_USER) && node.hasFlag(NodeFlag.ENABLED)
                && !node.hasFlag(NodeFlag.PASSWORD) && !node.hasFlag(NodeFlag.EDITABLE)
                && !node.textTruncated() && !node.text().isBlank()
                && node.windowId() == observation.windowId()
                && inside(node.bounds(), observation.windowBounds().orElseThrow())
                && !webAncestors(observation, node).isEmpty();
    }

    private static List<UiNode> webAncestors(Observation observation, UiNode selected) {
        List<UiNode> result = new ArrayList<>();
        int parentIndex = selected.parentIndex();
        while (parentIndex >= 0) {
            UiNode ancestor = null;
            for (UiNode node : observation.nodes()) {
                if (node.index() == parentIndex) {
                    ancestor = node;
                    break;
                }
            }
            if (ancestor == null || ancestor.index() >= selected.index()
                    || ancestor.windowId() != observation.windowId()
                    || !ancestor.hasFlag(NodeFlag.VISIBLE_TO_USER)
                    || !ancestor.hasFlag(NodeFlag.ENABLED) || ancestor.hasFlag(NodeFlag.PASSWORD)
                    || !inside(selected.bounds(), ancestor.bounds())) {
                return List.of();
            }
            result.add(ancestor);
            if (ancestor.className().endsWith("WebView")) {
                return result;
            }
            parentIndex = ancestor.parentIndex();
        }
        return List.of();
    }

    private static boolean inside(NodeBounds node, NodeBounds container) {
        return node.width() > 0 && node.height() > 0 && node.left() >= container.left()
                && node.top() >= container.top() && node.right() <= container.right()
                && node.bottom() <= container.bottom();
    }

    private static void addList(List<String> fields, List<String> values) {
        fields.add(Integer.toString(values.size()));
        fields.addAll(values);
    }

    private static UiNode find(Observation observation, NodeSelector selector)
            throws DeviceException {
        List<UiNode> matches = new ArrayList<>();
        for (UiNode node : observation.nodes()) {
            if (selector.matches(node)) {
                matches.add(node);
            }
        }
        if (matches.isEmpty()) {
            throw new DeviceException(DeviceException.Code.STALE,
                    "no node matches: " + selector.description());
        }
        if (matches.size() > 1) {
            throw new DeviceException(DeviceException.Code.REJECTED,
                    "ambiguous node selector: " + selector.description());
        }
        return matches.get(0);
    }
}
