package agent.phone.connection;

import android.os.Bundle;

/** Small receipt packet; the tap itself never carries content or an executable path. */
public final class TargetTapPackets {
    private TargetTapPackets() { }
    public static Bundle encode(TargetTap tap) {
        if (tap == null) throw malformed();
        Bundle packet = new Bundle();
        packet.putInt("display_id", tap.displayId);
        packet.putInt("window_id", tap.windowId);
        packet.putInt("x", tap.x);
        packet.putInt("y", tap.y);
        packet.putLong("at_nanos", tap.atNanos);
        packet.putString("observation_id", tap.observationId);
        ObservationPackets.requireSize(packet);
        return packet;
    }
    public static TargetTap decode(Bundle packet, DisplaySession expected, String expectedObservationId) {
        if (packet == null) throw malformed();
        ObservationPackets.requireSize(packet);
        TargetPackets.keys(packet, "display_id", "window_id", "x", "y", "at_nanos", "observation_id");
        TargetTap tap = new TargetTap(TargetPackets.integer(packet, "display_id"),
                TargetPackets.integer(packet, "window_id"), TargetPackets.integer(packet, "x"),
                TargetPackets.integer(packet, "y"), TargetPackets.number(packet, "at_nanos"),
                TargetPackets.string(packet, "observation_id"));
        tap.requireFor(expected, expectedObservationId);
        return tap;
    }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed tap packet"); }
}
