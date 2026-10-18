package agent.next.brain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A small, serial-execution task graph.
 *
 * <p>The graph is mutable: slow-loop replanning can add, replace, or remove
 * pending subgoals. Invariants are checked on every mutation so a bad replan
 * cannot leave an unknown dependency, a duplicate id, or a cycle behind.</p>
 *
 * <p>Only one node is active at a time. Completing a node does not advance the
 * graph automatically; the caller asks {@link #current()} or {@link #ready()}
 * again after recording effects.</p>
 */
public final class TaskGraph {

    public enum NodeStatus {
        PENDING,
        ACTIVE,
        COMPLETED,
        FAILED,
        SKIPPED
    }

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    public TaskGraph(Iterable<Subgoal> subgoals) {
        Objects.requireNonNull(subgoals, "subgoals");
        int count = 0;
        for (Subgoal subgoal : subgoals) {
            Objects.requireNonNull(subgoal, "subgoal");
            if (nodes.putIfAbsent(subgoal.id(), new Node(subgoal)) != null) {
                throw new IllegalArgumentException("duplicate subgoal id: " + subgoal.id());
            }
            count++;
        }
        if (count == 0) {
            throw new IllegalArgumentException("task graph must not be empty");
        }
        validateGraph();
    }

    public static TaskGraph of(Subgoal... subgoals) {
        return new TaskGraph(List.of(subgoals));
    }

    public void add(Subgoal subgoal) {
        Objects.requireNonNull(subgoal, "subgoal");
        if (nodes.containsKey(subgoal.id())) {
            throw new IllegalArgumentException("duplicate subgoal id: " + subgoal.id());
        }
        nodes.put(subgoal.id(), new Node(subgoal));
        try {
            validateGraph();
        } catch (RuntimeException failure) {
            nodes.remove(subgoal.id());
            throw failure;
        }
    }

    /**
     * Replaces a pending, failed, or skipped node with a new approach. The
     * replacement always starts PENDING so a failed node can be retried.
     */
    public void replace(Subgoal subgoal) {
        Objects.requireNonNull(subgoal, "subgoal");
        Node existing = requireNode(subgoal.id());
        if (existing.status == NodeStatus.ACTIVE || existing.status == NodeStatus.COMPLETED) {
            throw new IllegalStateException("cannot replace " + subgoal.id() + " in status " + existing.status);
        }
        Node replacement = new Node(subgoal, NodeStatus.PENDING);
        nodes.put(subgoal.id(), replacement);
        try {
            validateGraph();
        } catch (RuntimeException failure) {
            nodes.put(subgoal.id(), existing);
            throw failure;
        }
    }

    public void remove(String id) {
        Node node = requireNode(id);
        if (node.status == NodeStatus.ACTIVE || node.status == NodeStatus.COMPLETED) {
            throw new IllegalStateException("cannot remove " + id + " in status " + node.status);
        }
        for (Node other : nodes.values()) {
            if (other.subgoal.dependsOn().contains(id)) {
                throw new IllegalStateException("cannot remove " + id + "; " + other.subgoal.id() + " depends on it");
            }
        }
        nodes.remove(id);
    }

    /**
     * Inserts a repair/clarification node immediately before the current node.
     * The inserted node inherits the current node's dependencies; the current
     * node becomes dependent on the inserted node and starts over as PENDING.
     * The inserted node's own dependsOn set is ignored for this operation.
     */
    public void insertBefore(String currentId, Subgoal inserted) {
        Objects.requireNonNull(inserted, "inserted");
        Node current = requireNode(currentId);
        if (current.status == NodeStatus.ACTIVE || current.status == NodeStatus.COMPLETED
                || current.status == NodeStatus.SKIPPED) {
            throw new IllegalStateException("cannot insert before " + currentId
                    + " in status " + current.status);
        }
        if (inserted.id().equals(currentId) || nodes.containsKey(inserted.id())) {
            throw new IllegalArgumentException("inserted subgoal id is already present: " + inserted.id());
        }
        Map<String, Node> backup = copyNodes();
        try {
            Subgoal insertedNode = new Subgoal(inserted.id(), inserted.intent(),
                    current.subgoal.dependsOn(), inserted.preconditions(),
                    inserted.postconditions(), inserted.fallback(), inserted.risk(),
                    inserted.required());
            Set<String> currentDependencies = new LinkedHashSet<>(current.subgoal.dependsOn());
            currentDependencies.add(inserted.id());
            Subgoal updatedCurrent = new Subgoal(current.subgoal.id(), current.subgoal.intent(),
                    currentDependencies, current.subgoal.preconditions(),
                    current.subgoal.postconditions(), current.subgoal.fallback(),
                    current.subgoal.risk(), current.subgoal.required());
            nodes.put(inserted.id(), new Node(insertedNode));
            nodes.put(currentId, new Node(updatedCurrent, NodeStatus.PENDING));
            validateGraph();
        } catch (RuntimeException failure) {
            restore(backup);
            throw failure;
        }
    }

    /**
     * Removes a pending/failed/skipped node and rewires its dependents onto its
     * dependencies. Active/completed nodes and active/completed dependents are
     * rejected because their execution history cannot be rewritten.
     */
    public void removeAndRewire(String id) {
        Node removed = requireNode(id);
        if (removed.status == NodeStatus.ACTIVE || removed.status == NodeStatus.COMPLETED) {
            throw new IllegalStateException("cannot remove " + id + " in status " + removed.status);
        }
        for (Node other : nodes.values()) {
            if (other.subgoal.dependsOn().contains(id)
                    && (other.status == NodeStatus.ACTIVE || other.status == NodeStatus.COMPLETED)) {
                throw new IllegalStateException("cannot rewire executed dependent "
                        + other.subgoal.id());
            }
        }
        Map<String, Node> backup = copyNodes();
        try {
            nodes.remove(id);
            for (Map.Entry<String, Node> entry : nodes.entrySet()) {
                Node other = entry.getValue();
                if (!other.subgoal.dependsOn().contains(id)) {
                    continue;
                }
                Set<String> dependencies = new LinkedHashSet<>(other.subgoal.dependsOn());
                dependencies.remove(id);
                dependencies.addAll(removed.subgoal.dependsOn());
                Subgoal updated = new Subgoal(other.subgoal.id(), other.subgoal.intent(),
                        dependencies, other.subgoal.preconditions(),
                        other.subgoal.postconditions(), other.subgoal.fallback(),
                        other.subgoal.risk(), other.subgoal.required());
                entry.setValue(new Node(updated, other.status));
            }
            validateGraph();
        } catch (RuntimeException failure) {
            restore(backup);
            throw failure;
        }
    }

    public Optional<Subgoal> current() {
        for (Node node : nodes.values()) {
            if (node.status == NodeStatus.ACTIVE) {
                return Optional.of(node.subgoal);
            }
        }
        return ready().stream().findFirst();
    }

    public List<Subgoal> ready() {
        List<Subgoal> ready = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (node.status == NodeStatus.PENDING && dependenciesSatisfied(node.subgoal)) {
                ready.add(node.subgoal);
            }
        }
        return List.copyOf(ready);
    }

    public void activate(String id) {
        Node node = requireNode(id);
        if (node.status != NodeStatus.PENDING) {
            throw new IllegalStateException("cannot activate " + id + " in status " + node.status);
        }
        if (activeNode() != null) {
            throw new IllegalStateException("another subgoal is already active: " + activeNode().subgoal.id());
        }
        if (!dependenciesSatisfied(node.subgoal)) {
            throw new IllegalStateException("dependencies are not satisfied for " + id);
        }
        node.status = NodeStatus.ACTIVE;
    }

    public void complete(String id) {
        Node node = requireNode(id);
        if (node.status == NodeStatus.COMPLETED) {
            return;
        }
        if (node.status == NodeStatus.FAILED || node.status == NodeStatus.SKIPPED) {
            throw new IllegalStateException("cannot complete " + id + " in status " + node.status);
        }
        if (!dependenciesSatisfied(node.subgoal)) {
            throw new IllegalStateException("dependencies are not satisfied for " + id);
        }
        node.status = NodeStatus.COMPLETED;
    }

    public void fail(String id) {
        Node node = requireNode(id);
        if (node.status == NodeStatus.FAILED) {
            return;
        }
        if (node.status == NodeStatus.COMPLETED || node.status == NodeStatus.SKIPPED) {
            throw new IllegalStateException("cannot fail " + id + " in status " + node.status);
        }
        node.status = NodeStatus.FAILED;
    }

    public void skip(String id) {
        Node node = requireNode(id);
        if (node.subgoal.required()) {
            throw new IllegalArgumentException("required subgoal cannot be skipped: " + id);
        }
        if (node.status == NodeStatus.SKIPPED) {
            return;
        }
        if (node.status == NodeStatus.ACTIVE || node.status == NodeStatus.COMPLETED) {
            throw new IllegalStateException("cannot skip " + id + " in status " + node.status);
        }
        node.status = NodeStatus.SKIPPED;
    }

    public void reset(String id) {
        Node node = requireNode(id);
        if (node.status == NodeStatus.FAILED || node.status == NodeStatus.SKIPPED) {
            node.status = NodeStatus.PENDING;
        } else if (node.status != NodeStatus.PENDING) {
            throw new IllegalStateException("cannot reset " + id + " in status " + node.status);
        }
    }

    public NodeStatus status(String id) {
        return requireNode(id).status;
    }

    public int total() {
        return nodes.size();
    }

    public int completedCount() {
        int completed = 0;
        for (Node node : nodes.values()) {
            if (node.status == NodeStatus.COMPLETED) {
                completed++;
            }
        }
        return completed;
    }

    public double progress() {
        return (double) completedCount() / total();
    }

    public boolean isDone() {
        for (Node node : nodes.values()) {
            if (node.status == NodeStatus.COMPLETED) {
                continue;
            }
            if (node.status == NodeStatus.SKIPPED && !node.subgoal.required()) {
                continue;
            }
            return false;
        }
        return true;
    }

    public boolean hasFailed() {
        for (Node node : nodes.values()) {
            if (node.status == NodeStatus.FAILED) {
                return true;
            }
        }
        return false;
    }

    public boolean blocked() {
        return !isDone() && activeNode() == null && ready().isEmpty();
    }

    public List<Subgoal> remaining() {
        List<Subgoal> remaining = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (node.status != NodeStatus.COMPLETED && node.status != NodeStatus.SKIPPED) {
                remaining.add(node.subgoal);
            }
        }
        return List.copyOf(remaining);
    }

    public Map<String, NodeStatus> snapshot() {
        Map<String, NodeStatus> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : nodes.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().status);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private Node activeNode() {
        for (Node node : nodes.values()) {
            if (node.status == NodeStatus.ACTIVE) {
                return node;
            }
        }
        return null;
    }

    private boolean dependenciesSatisfied(Subgoal subgoal) {
        for (String dependency : subgoal.dependsOn()) {
            Node node = nodes.get(dependency);
            if (node == null) {
                return false;
            }
            if (node.status == NodeStatus.COMPLETED) {
                continue;
            }
            if (node.status == NodeStatus.SKIPPED && !node.subgoal.required()) {
                continue;
            }
            return false;
        }
        return true;
    }

    private Map<String, Node> copyNodes() {
        Map<String, Node> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry : nodes.entrySet()) {
            Node node = entry.getValue();
            copy.put(entry.getKey(), new Node(node.subgoal, node.status));
        }
        return copy;
    }

    private void restore(Map<String, Node> backup) {
        nodes.clear();
        nodes.putAll(backup);
    }

    private Node requireNode(String id) {
        Node node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("unknown subgoal: " + id);
        }
        return node;
    }

    private void validateGraph() {
        for (Node node : nodes.values()) {
            Subgoal subgoal = node.subgoal;
            for (String dependency : subgoal.dependsOn()) {
                if (dependency.equals(subgoal.id())) {
                    throw new IllegalArgumentException("subgoal " + subgoal.id() + " depends on itself");
                }
                if (!nodes.containsKey(dependency)) {
                    throw new IllegalArgumentException(
                            "unknown dependency " + dependency + " for subgoal " + subgoal.id());
                }
            }
        }
        validateAcyclic();
    }

    private void validateAcyclic() {
        Map<String, Visit> visits = new HashMap<>();
        for (String id : nodes.keySet()) {
            visit(id, visits, new HashSet<>());
        }
    }

    private void visit(String id, Map<String, Visit> visits, Set<String> path) {
        Visit state = visits.get(id);
        if (state == Visit.DONE) {
            return;
        }
        if (state == Visit.VISITING) {
            throw new IllegalArgumentException("cycle detected at subgoal " + id + " in path " + path);
        }
        visits.put(id, Visit.VISITING);
        path.add(id);
        for (String dependency : nodes.get(id).subgoal.dependsOn()) {
            visit(dependency, visits, path);
        }
        path.remove(id);
        visits.put(id, Visit.DONE);
    }

    private enum Visit {
        VISITING,
        DONE
    }

    private static final class Node {
        private final Subgoal subgoal;
        private NodeStatus status;

        private Node(Subgoal subgoal) {
            this(subgoal, NodeStatus.PENDING);
        }

        private Node(Subgoal subgoal, NodeStatus status) {
            this.subgoal = subgoal;
            this.status = status;
        }
    }
}
