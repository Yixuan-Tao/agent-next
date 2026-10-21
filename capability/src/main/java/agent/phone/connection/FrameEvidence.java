package agent.phone.connection;

import agent.next.device.DisplaySpec;

/** Immutable provenance for one newly sampled authorized display image. */
public final class FrameEvidence {
    private static final DisplaySpec STANDARD_DISPLAY = DisplaySpec.standard();
    public static final int WIDTH = STANDARD_DISPLAY.width();
    public static final int HEIGHT = STANDARD_DISPLAY.height();
    public static final int MAX_PNG_BYTES = 9 * 1024 * 1024;
    public final TargetSession target;
    public final String frameId, observationId;
    public final long sequence, startedNanos, endedNanos;
    public final int focusedWindowId, width, height, byteLength;
    public FrameEvidence(TargetSession target,String frameId,long sequence,long startedNanos,long endedNanos,
            String observationId,int focusedWindowId,int width,int height,int byteLength) {
        if (target == null || target.display.status != DisplaySession.Status.ACTIVE || !id(frameId) || !id(observationId)
                || sequence <= 0 || startedNanos <= 0 || endedNanos < startedNanos || focusedWindowId < 0
                || width != WIDTH || height != HEIGHT || target.display.width != width || target.display.height != height
                || byteLength < 33 || byteLength > MAX_PNG_BYTES) throw new IllegalArgumentException("Malformed frame evidence");
        this.target = target; this.frameId = frameId; this.sequence = sequence; this.startedNanos = startedNanos;
        this.endedNanos = endedNanos; this.observationId = observationId; this.focusedWindowId = focusedWindowId;
        this.width = width; this.height = height; this.byteLength = byteLength;
    }
    public void requireFresh(TargetSession expected,long previousSequence) {
        if (expected == null || !target.sameIdentity(expected) || sequence <= previousSequence) {
            throw new IllegalArgumentException("Stale frame identity or sequence");
        }
    }
    private static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9_.:-]{1,64}"); }
    public static final class Capture {
        public final FrameEvidence evidence;
        private final byte[] png;
        public Capture(FrameEvidence evidence,byte[] png) {
            if (evidence == null || png == null || png.length != evidence.byteLength) throw new IllegalArgumentException("Invalid PNG length");
            requirePngHeader(png);
            this.evidence = evidence; this.png = png.clone();
        }
        public byte[] png() { return png.clone(); }
    }
    public static void requirePngHeader(byte[] png) {
        if (png == null || png.length < 33 || png.length > MAX_PNG_BYTES) throw new IllegalArgumentException("Invalid PNG size");
        int[] signature = {137,80,78,71,13,10,26,10};
        for (int i = 0; i < signature.length; i++) if ((png[i] & 255) != signature[i]) throw new IllegalArgumentException("Invalid PNG signature");
        if (integer(png,8) != 13 || integer(png,12) != 0x49484452 || integer(png,16) != WIDTH
                || integer(png,20) != HEIGHT || (png[24] & 255) != 8 || ((png[25] & 255) != 2 && (png[25] & 255) != 6)
                || png[26] != 0 || png[27] != 0 || png[28] != 0) throw new IllegalArgumentException("Invalid PNG header");
    }
    private static int integer(byte[] bytes,int at) {
        return (bytes[at] & 255) << 24 | (bytes[at + 1] & 255) << 16 | (bytes[at + 2] & 255) << 8 | bytes[at + 3] & 255;
    }
}
