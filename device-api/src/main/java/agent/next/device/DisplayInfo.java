package agent.next.device;

/** Verified display identity returned by the implementation. */
public final class DisplayInfo {

    private final int displayId;
    private final String uniqueId;
    private final String name;
    private final int width;
    private final int height;
    private final int densityDpi;
    private final int ownerUid;
    private final long generation;

    public DisplayInfo(int displayId, String uniqueId, String name, int width, int height,
            int densityDpi, int ownerUid, long generation) {
        if (displayId < 0) {
            throw new IllegalArgumentException("displayId must not be negative");
        }
        this.uniqueId = Text.require(uniqueId, "uniqueId");
        this.name = Text.require(name, "name");
        Text.requirePositive(width, "width");
        Text.requirePositive(height, "height");
        Text.requirePositive(densityDpi, "densityDpi");
        if (ownerUid < 0) {
            throw new IllegalArgumentException("ownerUid must not be negative");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("generation must be positive");
        }
        this.displayId = displayId;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
        this.ownerUid = ownerUid;
        this.generation = generation;
    }

    public int displayId() {
        return displayId;
    }

    public String uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int densityDpi() {
        return densityDpi;
    }

    public int ownerUid() {
        return ownerUid;
    }

    public long generation() {
        return generation;
    }

    @Override
    public String toString() {
        return "DisplayInfo{" + displayId + ", " + name + ", " + width + "x" + height + '}';
    }
}
