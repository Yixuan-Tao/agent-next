package agent.next.brain.action;

import agent.next.brain.loop.NodeSelector;
import agent.next.device.DeviceException;
import agent.next.device.NodeBounds;
import agent.next.device.NodeFlag;
import agent.next.device.Observation;
import agent.next.device.UiNode;

import java.util.ArrayList;
import java.util.List;

/** A model-selected current node, rebound by observed identity rather than guessed vocabulary. */
public final class ObservedNodeSelector implements NodeSelector {
    private final List<UiNode> ancestry;
    private final String identity;
    private final boolean searchControl;

    public ObservedNodeSelector(Observation observation, int index) {
        List<UiNode> path = new ArrayList<>();
        int next = index;
        while (next >= 0) {
            UiNode node = at(observation, next);
            if (node == null) throw new IllegalArgumentException("unknown observed node " + next);
            path.add(node);
            next = node.parentIndex();
        }
        if (path.isEmpty()) throw new IllegalArgumentException("missing observed node");
        ancestry = List.copyOf(path);
        searchControl = isSearchControl(ancestry);
        List<String> fields = new ArrayList<>();
        for (UiNode node : ancestry) {
            fields.addAll(List.of(Integer.toString(node.index()), Integer.toString(node.parentIndex()),
                    node.viewId(), node.className()));
            if (!searchControl) {
                fields.add(node.text());
                fields.add(node.contentDescription());
            }
            if (!searchControl) {
                fields.addAll(List.of(node.bounds().toString(), node.flags().stream().map(Enum::name).sorted()
                        .collect(java.util.stream.Collectors.toList()).toString()));
            }
        }
        identity = GroundedAction.fingerprint(fields);
    }

    @Override
    public boolean matches(UiNode node) {
        return same(ancestry.get(0), node, searchControl);
    }

    /** Search controls are identified by their control structure, not rotating prompt text. */
    public boolean isSearchControl() {
        return searchControl;
    }

    @Override
    public String description() {
        return "observed node " + ancestry.get(0).index() + " identity=" + identity;
    }

    /** The returned path includes the visible label and the actual actionable ancestor. */
    public List<UiNode> resolve(Observation observation, NodeFlag required) throws DeviceException {
        List<UiNode> matches = observation.nodes().stream().filter(this::matches).toList();
        if (matches.isEmpty()) {
            throw new DeviceException(DeviceException.Code.STALE,"selected node changed or disappeared");
        }
        if (matches.size() != 1) {
            throw new DeviceException(DeviceException.Code.REJECTED,"selected node is ambiguous on the current page");
        }
        List<UiNode> path = new ArrayList<>();
        UiNode fresh = matches.get(0);
        for (UiNode original : ancestry) {
            if (fresh == null || !same(original, fresh, searchControl)) {
                throw new DeviceException(DeviceException.Code.STALE, "selected node/ancestor changed");
            }
            if (!fresh.hasFlag(NodeFlag.VISIBLE_TO_USER) || !fresh.hasFlag(NodeFlag.ENABLED)
                    || fresh.hasFlag(NodeFlag.PASSWORD) || fresh.windowId() != observation.windowId()
                    || !inside(fresh.bounds(), observation.windowBounds().orElseThrow())) {
                throw new DeviceException(DeviceException.Code.REJECTED, "node is not safely actionable");
            }
            path.add(fresh);
            if (fresh.hasFlag(required)) return List.copyOf(path);
            // Text input must target the selected editor itself, never an ancestor.
            if (required != NodeFlag.CLICKABLE) break;
            fresh = at(observation,fresh.parentIndex());
        }
        throw new DeviceException(DeviceException.Code.REJECTED,
                "selected node has no " + required + " affordance; choose another observed node");
    }

    private static UiNode at(Observation observation, int index) {
        return observation.nodes().stream().filter(node -> node.index() == index).findFirst().orElse(null);
    }

    private static boolean same(UiNode expected, UiNode actual) {
        // Bounds and affordance flags can change during layout/animation. Rebind by stable
        // accessibility identity, then validate the current bounds and required flag above.
        return expected.windowId() == actual.windowId() && expected.viewId().equals(actual.viewId())
                && expected.className().equals(actual.className()) && expected.text().equals(actual.text())
                && expected.contentDescription().equals(actual.contentDescription())
                && expected.textTruncated() == actual.textTruncated();
    }

    private static boolean same(UiNode expected, UiNode actual, boolean ignoreDynamicText) {
        if (!ignoreDynamicText) return same(expected, actual);
        return expected.windowId() == actual.windowId()
                && expected.viewId().equals(actual.viewId())
                && expected.className().equals(actual.className());
    }

    private static boolean isSearchControl(List<UiNode> ancestry) {
        for (int i = 0; i < ancestry.size(); i++) {
            UiNode node = ancestry.get(i);
            boolean targetOrControl = i == 0 || node.hasFlag(NodeFlag.CLICKABLE)
                    || node.hasFlag(NodeFlag.EDITABLE);
            if (targetOrControl && (containsSearchTerm(node.viewId())
                    || containsSearchTerm(node.className()) || containsSearchTerm(node.text())
                    || containsSearchTerm(node.contentDescription()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSearchTerm(String value) {
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("search") || value.contains("搜索")
                || value.contains("查询") || value.contains("查找");
    }

    private static boolean inside(NodeBounds node, NodeBounds outer) {
        return node.width() > 0 && node.height() > 0 && node.left() >= outer.left()
                && node.top() >= outer.top() && node.right() <= outer.right() && node.bottom() <= outer.bottom();
    }
}
