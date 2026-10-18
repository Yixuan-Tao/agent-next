package agent.next.device;

/** Immutable rectangle in logical display pixels. */
public final class NodeBounds {

    public static final NodeBounds EMPTY = new NodeBounds(0, 0, 0, 0);

    private final int left;
    private final int top;
    private final int right;
    private final int bottom;

    public NodeBounds(int left, int top, int right, int bottom) {
        if (left > right || top > bottom) {
            throw new IllegalArgumentException("bounds must not be inverted");
        }
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int left() {
        return left;
    }

    public int top() {
        return top;
    }

    public int right() {
        return right;
    }

    public int bottom() {
        return bottom;
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public int centerX() {
        return left + width() / 2;
    }

    public int centerY() {
        return top + height() / 2;
    }

    public boolean contains(Point point) {
        if (point == null) {
            return false;
        }
        return point.x() >= left && point.x() < right && point.y() >= top && point.y() < bottom;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof NodeBounds)) {
            return false;
        }
        NodeBounds other = (NodeBounds) value;
        return left == other.left && top == other.top
                && right == other.right && bottom == other.bottom;
    }

    @Override
    public int hashCode() {
        return (((left * 31) + top) * 31 + right) * 31 + bottom;
    }

    @Override
    public String toString() {
        return "NodeBounds{" + left + ", " + top + ", " + right + ", " + bottom + "}";
    }
}
