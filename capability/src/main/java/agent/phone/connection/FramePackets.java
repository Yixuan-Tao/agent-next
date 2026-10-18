package agent.phone.connection;

import android.os.Bundle;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.nio.ByteBuffer;

/** Metadata stays small in Binder; the sealed image descriptor is consumed on the RPC worker. */
public final class FramePackets {
    private FramePackets() { }
    public static Bundle encode(FrameEvidence frame,SharedMemory memory) {
        if (frame == null || memory == null) throw malformed();
        Bundle packet = new Bundle();
        packet.putBundle("target",TargetPackets.encode(frame.target));
        packet.putString("frame_id",frame.frameId); packet.putLong("sequence",frame.sequence);
        packet.putLong("started_nanos",frame.startedNanos); packet.putLong("ended_nanos",frame.endedNanos);
        packet.putString("observation_id",frame.observationId); packet.putInt("focused_window_id",frame.focusedWindowId);
        packet.putInt("width",frame.width); packet.putInt("height",frame.height); packet.putInt("byte_length",frame.byteLength);
        packet.putParcelable("png",memory);
        ObservationPackets.requireSize(packet);
        return packet;
    }
    public static FrameEvidence.Capture decode(Bundle packet,TargetSession expected,long previousSequence) {
        ByteBuffer mapped = null;
        try {
            ObservationPackets.requireSize(packet);
            TargetPackets.keys(packet,"target","frame_id","sequence","started_nanos","ended_nanos","observation_id",
                    "focused_window_id","width","height","byte_length","png");
            if (expected == null || !(packet.get("target") instanceof Bundle) || !(packet.get("png") instanceof SharedMemory)) throw malformed();
            TargetSession target = TargetPackets.decode((Bundle) packet.get("target"),expected.display,expected.taskId,expected.packageName);
            FrameEvidence frame = new FrameEvidence(target,TargetPackets.string(packet,"frame_id"),TargetPackets.number(packet,"sequence"),
                    TargetPackets.number(packet,"started_nanos"),TargetPackets.number(packet,"ended_nanos"),
                    TargetPackets.string(packet,"observation_id"),TargetPackets.integer(packet,"focused_window_id"),
                    TargetPackets.integer(packet,"width"),TargetPackets.integer(packet,"height"),TargetPackets.integer(packet,"byte_length"));
            frame.requireFresh(expected,previousSequence);
            SharedMemory memory = (SharedMemory) packet.get("png");
            if (memory.getSize() != frame.byteLength) throw malformed();
            requireReadOnly(memory);
            mapped = memory.mapReadOnly();
            if (mapped.remaining() != frame.byteLength) throw malformed();
            byte[] png = new byte[frame.byteLength]; mapped.get(png);
            return new FrameEvidence.Capture(frame,png);
        } catch (ErrnoException failure) { throw malformed(); }
        finally {
            if (mapped != null) SharedMemory.unmap(mapped);
            closeMemories(packet);
        }
    }
    private static void requireReadOnly(SharedMemory memory) {
        ByteBuffer writable = null;
        try { writable = memory.mapReadWrite(); }
        catch (ErrnoException denied) {
            if (denied.errno == OsConstants.EPERM || denied.errno == OsConstants.EACCES) return;
            // Any other errno (ENOMEM, EBADF, ...) proves nothing about sealing, so it cannot be accepted.
            throw new IllegalArgumentException("Frame memory is not verifiably read-only", denied);
        }
        finally { if (writable != null) SharedMemory.unmap(writable); }
        throw malformed();
    }
    private static void closeMemories(Bundle packet) {
        if (packet == null) return;
        // Even an unexpected top-level descriptor is owned by this received packet.
        for (String key : packet.keySet()) {
            Object value = packet.get(key);
            if (value instanceof SharedMemory) ((SharedMemory) value).close();
            else if (value instanceof Bundle) closeMemories((Bundle) value);
        }
    }
    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Malformed frame packet"); }
}
