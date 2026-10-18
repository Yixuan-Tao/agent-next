package agent.next.device;

/** Requested private-display geometry. The implementation may reject unsupported values. */
public final class DisplaySpec {

    private static final int MAX_DIMENSION = 4096;
    private static final int MAX_DENSITY = 1000;

    private final int width;
    private final int height;
    private final int densityDpi;

    public DisplaySpec(int width, int height, int densityDpi) {
        Text.requirePositive(width, "width");
        Text.requirePositive(height, "height");
        Text.requirePositive(densityDpi, "densityDpi");
        if (width > MAX_DIMENSION || height > MAX_DIMENSION || densityDpi > MAX_DENSITY) {
            throw new IllegalArgumentException("display geometry is outside the supported range");
        }
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
    }

    /** Current capability default: 720 x 1280, 240 dpi. */
    public static DisplaySpec standard() {
        return new DisplaySpec(720, 1280, 240);
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

    @Override
    public String toString() {
        return "DisplaySpec{" + width + "x" + height + " @" + densityDpi + "dpi}";
    }
}
