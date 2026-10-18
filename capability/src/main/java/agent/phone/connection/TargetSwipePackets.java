package agent.phone.connection;

import android.os.Bundle;

/** Small receipt packet; swipe content never carries an executable path. */
public final class TargetSwipePackets {
    private TargetSwipePackets() { }
    public static Bundle encode(TargetSwipe swipe) {
        if (swipe == null) throw malformed();
        Bundle packet = new Bundle();
        packet.putInt("display_id", swipe.displayId);
        packet.putInt("window_id", swipe.windowId);
        packet.putInt("start_x", swipe.startX);
        packet.putInt("start_y", swipe.startY);
        packet.putInt("end_x", swipe.endX);
        packet.putInt("end_y", swipe.endY);
        packet.putInt("duration_ms", swipe.durationMs);
        packet.putLong("at_nanos", swipe.atNanos);
        packet.putString("observation_id", swipe.observationId);
        ObservationPackets.requireSize(packet);
        return packet;
    }
    public static TargetSwipe decode(Bundle packet, DisplaySession expected, String observationId) {
        if (packet == null) throw malformed();
        ObservationPackets.requireSize(packet);
        TargetPackets.keys(packet, "display_id", "window_id", "start_x", "start_y", "end_x", "end_y",
                "duration_ms", "at_nanos", "observation_id");
        TargetSwipe swipe = new TargetSwipe(TargetPackets.integer(packet, "display_id"),
                TargetPackets.integer(packet, "window_id"), TargetPackets.integer(packet, "start_x"),
                TargetPackets.integer(packet, "start_y"), TargetPackets.integer(packet, "end_x"),
                TargetPackets.integer(packet, "end_y"), TargetPackets.integer(packet, "duration_ms"),
                TargetPackets.number(packet, "at_nanos"), TargetPackets.string(packet, "observation_id"));
        swipe.requireFor(expected, observationId);
        return swipe;
    }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed swipe packet"); }
}
