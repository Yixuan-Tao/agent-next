package agent.phone.connection;

import agent.next.device.DisplaySpec;

/** Service-issued identity. A numeric display ID alone never authorizes an operation. */
public final class DisplaySession {
    public enum Status { ACTIVE, INVALID, RELEASED, CLEANUP_FAILED }
    public final String token, uniqueId;
    public final long generation;
    public final int displayId, ownerUid, width, height, densityDpi, flags;
    public final Status status;
    public DisplaySession(String token, long generation, int displayId, String uniqueId,
            int ownerUid, int width, int height, int densityDpi, int flags, Status status) {
        DisplaySpec spec = DisplaySpec.standard();
        if (token == null || token.isEmpty() || token.length() > 128 || generation <= 0
                || displayId <= 0 || uniqueId == null || uniqueId.isEmpty() || uniqueId.length() > 512
                || ownerUid != 2000 || width != spec.width() || height != spec.height()
                || densityDpi != spec.densityDpi()
                || flags < 0 || status == null) throw new IllegalArgumentException("Malformed display session");
        this.token = token; this.generation = generation; this.displayId = displayId;
        this.uniqueId = uniqueId; this.ownerUid = ownerUid; this.width = width;
        this.height = height; this.densityDpi = densityDpi; this.flags = flags; this.status = status;
    }
    public boolean sameIdentity(DisplaySession other) {
        return other != null && token.equals(other.token) && generation == other.generation
                && displayId == other.displayId && uniqueId.equals(other.uniqueId);
    }
}
