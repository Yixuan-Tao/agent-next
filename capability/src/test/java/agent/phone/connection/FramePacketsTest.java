package agent.phone.connection;

import android.os.Bundle;
import android.os.SharedMemory;
import android.system.OsConstants;
import java.nio.ByteBuffer;

public final class FramePacketsTest {
    public static void main(String[] args) throws Exception {
        TargetSession target = FrameEvidenceTest.target("target",1);
        SharedMemory valid = memory(FrameEvidenceTest.png(),true);
        Bundle packet = FramePackets.encode(FrameEvidenceTest.frame(target,1,45),valid);
        FrameEvidence.Capture capture = FramePackets.decode(packet,target,0);
        check(capture.evidence.sequence == 1 && valid.closed && SharedMemory.mappings == 0,"decode closes FD and mapping");
        SharedMemory stale = memory(FrameEvidenceTest.png(),true);
        fails(() -> FramePackets.decode(FramePackets.encode(FrameEvidenceTest.frame(target,1,45),stale),target,1));
        check(stale.closed,"stale resource closed");
        SharedMemory unknown = memory(FrameEvidenceTest.png(),true),extra = memory(new byte[1],true);
        Bundle bad = FramePackets.encode(FrameEvidenceTest.frame(target,1,45),unknown); bad.putParcelable("extra",extra);
        fails(() -> FramePackets.decode(bad,target,0));
        check(unknown.closed && extra.closed,"unknown keys cannot retain frame resources");
        SharedMemory writable = memory(FrameEvidenceTest.png(),false);
        fails(() -> FramePackets.decode(FramePackets.encode(FrameEvidenceTest.frame(target,1,45),writable),target,0));
        check(writable.closed && SharedMemory.mappings == 0,"writable memory rejected and unmapped");
        SharedMemory wrongSize = memory(new byte[46],true);
        fails(() -> FramePackets.decode(FramePackets.encode(FrameEvidenceTest.frame(target,1,45),wrongSize),target,0));
        check(wrongSize.closed,"wrong length closes FD");
        SharedMemory malformed = memory(new byte[45],true);
        fails(() -> FramePackets.decode(FramePackets.encode(FrameEvidenceTest.frame(target,1,45),malformed),target,0));
        check(malformed.closed && SharedMemory.mappings == 0,"malformed PNG closes mapping and FD");
        SharedMemory unavailable = memory(FrameEvidenceTest.png(),false); unavailable.writeMappingErrno = OsConstants.ENOMEM;
        fails(() -> FramePackets.decode(FramePackets.encode(FrameEvidenceTest.frame(target,1,45),unavailable),target,0));
        check(unavailable.closed,"arbitrary map failure does not prove sealing");
        System.out.println("FramePacketsTest: PASS (strict fields, replay, read-only memory, size, cleanup)");
    }
    static SharedMemory memory(byte[] bytes,boolean seal) throws Exception {
        SharedMemory memory = SharedMemory.create("frame",bytes.length); ByteBuffer mapped = memory.mapReadWrite();
        mapped.put(bytes); SharedMemory.unmap(mapped); if (seal) memory.setProtect(OsConstants.PROT_READ); return memory;
    }
    static void fails(Runnable action) { try { action.run(); throw new AssertionError("expected rejection"); } catch (IllegalArgumentException expected) { } }
    static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
