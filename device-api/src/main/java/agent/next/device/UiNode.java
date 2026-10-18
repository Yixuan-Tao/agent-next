package agent.next.device;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** One node in an observation. {@code ref} is service-issued and must be used verbatim for input. */
public final class UiNode {

    private final String ref;
    private final int index;
    private final int parentIndex;
    private final int windowId;
    private final String viewId;
    private final String className;
    private final String text;
    private final String contentDescription;
    private final NodeBounds bounds;
    private final Set<NodeFlag> flags;
    private final boolean textTruncated;

    public UiNode(String ref, int index, int parentIndex, int windowId,
            String viewId, String className, String text, String contentDescription,
            NodeBounds bounds, Set<NodeFlag> flags, boolean textTruncated) {
        this.ref = Text.require(ref, "ref");
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        if (parentIndex < -1 || parentIndex >= index) {
            throw new IllegalArgumentException("parentIndex must be -1 or before the node");
        }
        if (windowId < 0) {
            throw new IllegalArgumentException("windowId must not be negative");
        }
        if (viewId == null || className == null || text == null || contentDescription == null) {
            throw new NullPointerException("node text fields must not be null");
        }
        if (bounds == null) {
            throw new NullPointerException("bounds");
        }
        if (flags == null) {
            throw new NullPointerException("flags");
        }
        EnumSet<NodeFlag> copy = EnumSet.noneOf(NodeFlag.class);
        copy.addAll(flags);
        this.index = index;
        this.parentIndex = parentIndex;
        this.windowId = windowId;
        this.viewId = viewId;
        this.className = className;
        this.text = text;
        this.contentDescription = contentDescription;
        this.bounds = bounds;
        this.flags = Collections.unmodifiableSet(copy);
        this.textTruncated = textTruncated;
    }

    public String ref() {
        return ref;
    }

    public int index() {
        return index;
    }

    public int parentIndex() {
        return parentIndex;
    }

    public int windowId() {
        return windowId;
    }

    public String viewId() {
        return viewId;
    }

    public String className() {
        return className;
    }

    public String text() {
        return text;
    }

    public String contentDescription() {
        return contentDescription;
    }

    public NodeBounds bounds() {
        return bounds;
    }

    public Set<NodeFlag> flags() {
        return flags;
    }

    public boolean hasFlag(NodeFlag flag) {
        return flags.contains(flag);
    }

    public boolean textTruncated() {
        return textTruncated;
    }

    @Override
    public String toString() {
        return "UiNode{" + ref + ", " + className + ", text='" + text + "'}";
    }
}
