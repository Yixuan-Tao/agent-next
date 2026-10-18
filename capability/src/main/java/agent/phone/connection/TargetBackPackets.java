package agent.phone.connection;

import android.os.Bundle;

/** Small receipt packet for a back key action. */
public final class TargetBackPackets {
    private TargetBackPackets() { }
    public static Bundle encode(TargetBack back) {
        if (back == null) throw malformed();
        Bundle packet = new Bundle();
        packet.putInt("display_id", back.displayId);
        packet.putInt("window_id", back.windowId);
        packet.putLong("at_nanos", back.atNanos);
        packet.putString("observation_id", back.observationId);
        ObservationPackets.requireSize(packet);
        return packet;
    }
    public static TargetBack decode(Bundle packet, DisplaySession expected, String observationId) {
        if (packet == null) throw malformed();
        ObservationPackets.requireSize(packet);
        TargetPackets.keys(packet, "display_id", "window_id", "at_nanos", "observation_id");
        TargetBack back = new TargetBack(TargetPackets.integer(packet, "display_id"),
                TargetPackets.integer(packet, "window_id"), TargetPackets.number(packet, "at_nanos"),
                TargetPackets.string(packet, "observation_id"));
        back.requireFor(expected, observationId);
        return back;
    }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed back packet"); }
}
