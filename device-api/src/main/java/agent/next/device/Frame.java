package agent.next.device;

import java.time.Instant;

/** One captured frame. The PNG array is copied on construction and on access. */
public final class Frame {

    private final int width;
    private final int height;
    private final byte[] png;
    private final Instant capturedAt;
    private final long sequence;

    public Frame(int width, int height, byte[] png, Instant capturedAt, long sequence) {
        Text.requirePositive(width, "width");
        Text.requirePositive(height, "height");
        if (png == null || png.length == 0) {
            throw new IllegalArgumentException("png must not be empty");
        }
        if (capturedAt == null) {
            throw new NullPointerException("capturedAt");
        }
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        this.width = width;
        this.height = height;
        this.png = png.clone();
        this.capturedAt = capturedAt;
        this.sequence = sequence;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public byte[] png() {
        return png.clone();
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public long sequence() {
        return sequence;
    }

    @Override
    public String toString() {
        return "Frame{" + width + "x" + height + ", bytes=" + png.length
                + ", sequence=" + sequence + '}';
    }
}
