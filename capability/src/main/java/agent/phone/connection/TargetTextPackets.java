package agent.phone.connection;

import android.os.Bundle;

/** Small receipt packet; text content never returns through IPC. */
public final class TargetTextPackets {
    private TargetTextPackets() { }
    public static Bundle encode(TargetText text) {
        if (text == null) throw malformed();
        Bundle packet = new Bundle();
        packet.putInt("display_id", text.displayId);
        packet.putInt("window_id", text.windowId);
        packet.putString("ref", text.ref);
        packet.putInt("text_length", text.textLength);
        packet.putLong("at_nanos", text.atNanos);
        packet.putString("observation_id", text.observationId);
        ObservationPackets.requireSize(packet);
        return packet;
    }
    public static TargetText decode(Bundle packet, DisplaySession expected, String observationId, String ref) {
        if (packet == null) throw malformed();
        ObservationPackets.requireSize(packet);
        TargetPackets.keys(packet, "display_id", "window_id", "ref", "text_length", "at_nanos", "observation_id");
        TargetText text = new TargetText(TargetPackets.integer(packet, "display_id"),
                TargetPackets.integer(packet, "window_id"), TargetPackets.string(packet, "ref"),
                TargetPackets.integer(packet, "text_length"), TargetPackets.number(packet, "at_nanos"),
                TargetPackets.string(packet, "observation_id"));
        text.requireFor(expected, observationId, ref);
        return text;
    }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed text packet"); }
}
