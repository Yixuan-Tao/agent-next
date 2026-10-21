package agent.phone.connection;

import android.os.Bundle;

/** Small receipt packet; the tap itself never carries content or an executable path. */
public final class TargetTapPackets {
    private TargetTapPackets() { }
    public static Bundle stale(TargetTap.StaleException failure) {
        Bundle packet = new Bundle();
        packet.putString("input_error","STALE");
        packet.putInt("display_id",failure.displayId);
        packet.putString("observation_id",failure.observationId);
        return packet;
    }
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
        if (packet.containsKey("input_error")) {
            TargetPackets.keys(packet,"input_error","display_id","observation_id");
            if (!"STALE".equals(TargetPackets.string(packet,"input_error")) || expected == null
                    || expected.status != DisplaySession.Status.ACTIVE
                    || TargetPackets.integer(packet,"display_id") != expected.displayId
                    || expectedObservationId == null
                    || !expectedObservationId.equals(TargetPackets.string(packet,"observation_id"))) throw malformed();
            throw new TargetTap.StaleException(expected.displayId,expectedObservationId,null);
        }
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
