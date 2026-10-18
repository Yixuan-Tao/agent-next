package agent.phone.connection;

import android.os.Bundle;
import android.os.Parcel;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.File;
import java.nio.ByteBuffer;

/** Temporary device probe: real Parcel/SharedMemory copies with malformed frame packets. */
public final class ParcelFailureDiagnostic {
    private static final TargetSession TARGET = new TargetSession(
            new DisplaySession("display",1,4,"owned",2000,720,1280,240,1,DisplaySession.Status.ACTIVE),
            "task","target",1,"com.example.first",10123,"com.example.first.Main");
    private static final byte[] PNG = fakePng();
    private static int baseline;

    public static void main(String[] args) {
        try {
            valid("warmup",false);
            baseline = fdCount();
            System.out.println("baseline_fd=" + baseline);
            valid("valid",true);
            missingKey();
            wrongType();
            unknownKey();
            staleSequence();
            wrongLength();
            corruptPng();
            writableMemory();
            oversizedLength();
            valid("valid_after_failures",true);
            System.out.println("ParcelFailureDiagnostic: PASS");
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void valid(String name,boolean checkLeak) throws Exception {
        SharedMemory memory = memory(PNG,true);
        Bundle received = parceled(frame(2), memory);
        FrameEvidence.Capture capture = FramePackets.decode(received,TARGET,1);
        check(capture.evidence.sequence == 2 && capture.png().length == PNG.length,"valid frame decoded");
        if (checkLeak) expectNoLeak(name);
    }

    private static void missingKey() throws Exception {
        SharedMemory memory = memory(PNG,true);
        Bundle received = parceled(frame(2), memory);
        SharedMemory attached = (SharedMemory) received.get("png");
        attached.close();
        received.remove("png");
        expectRejected(() -> FramePackets.decode(received,TARGET,1));
        expectNoLeak("missing_png_key");
    }

    private static void wrongType() throws Exception {
        SharedMemory memory = memory(PNG,true);
        Bundle received = parceled(frame(2), memory);
        ((SharedMemory) received.get("png")).close();
        received.putString("png","not-memory");
        expectRejected(() -> FramePackets.decode(received,TARGET,1));
        expectNoLeak("wrong_png_type");
    }

    private static void unknownKey() throws Exception {
        SharedMemory memory = memory(PNG,true);
        Bundle received = parceled(frame(2), memory);
        received.putParcelable("extra",memory(new byte[1],true));
        expectRejected(() -> FramePackets.decode(received,TARGET,1));
        expectNoLeak("unknown_png_key");
    }

    private static void staleSequence() throws Exception {
        SharedMemory memory = memory(PNG,true);
        Bundle received = parceled(frame(2), memory);
        expectRejected(() -> FramePackets.decode(received,TARGET,2));
        expectNoLeak("stale_sequence");
    }

    private static void wrongLength() throws Exception {
        SharedMemory memory = memory(PNG,true);
        Bundle received = parceled(frame(2), memory);
        received.putInt("byte_length",PNG.length + 1);
        expectRejected(() -> FramePackets.decode(received,TARGET,1));
        expectNoLeak("byte_length_mismatch");
    }

    private static void corruptPng() throws Exception {
        byte[] corrupt = PNG.clone();
        corrupt[0] = 0;
        SharedMemory memory = memory(corrupt,true);
        Bundle received = parceled(frame(2), memory);
        expectRejected(() -> FramePackets.decode(received,TARGET,1));
        expectNoLeak("corrupt_png_header");
    }

    private static void writableMemory() throws Exception {
        SharedMemory memory = memory(PNG,false);
        Bundle received = parceled(frame(2), memory);
        expectRejected(() -> FramePackets.decode(received,TARGET,1));
        expectNoLeak("writable_memory");
    }

    private static void oversizedLength() throws Exception {
        SharedMemory memory = memory(PNG,true);
        Bundle received = parceled(frame(2), memory);
        received.putInt("byte_length",FrameEvidence.MAX_PNG_BYTES + 1);
        expectRejected(() -> FramePackets.decode(received,TARGET,1));
        expectNoLeak("oversized_length");
    }

    private static FrameEvidence frame(long sequence) {
        return new FrameEvidence(TARGET,"frame",sequence,3,4,"observation",6,720,1280,PNG.length);
    }

    private static Bundle parceled(FrameEvidence frame,SharedMemory memory) throws ErrnoException {
        Bundle packet = FramePackets.encode(frame,memory);
        Parcel parcel = Parcel.obtain();
        try {
            packet.writeToParcel(parcel,0);
            parcel.setDataPosition(0);
            return Bundle.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
            memory.close();
        }
    }

    private static SharedMemory memory(byte[] bytes,boolean seal) throws ErrnoException {
        SharedMemory memory = SharedMemory.create("agent-frame-diagnostic",bytes.length);
        ByteBuffer mapped = memory.mapReadWrite();
        try { mapped.put(bytes); }
        finally { SharedMemory.unmap(mapped); }
        if (seal && !memory.setProtect(OsConstants.PROT_READ)) throw new IllegalStateException("seal failed");
        return memory;
    }

    private static int fdCount() {
        String[] entries = new File("/proc/self/fd").list();
        if (entries == null) throw new IllegalStateException("fd list unavailable");
        return entries.length;
    }

    private static void expectRejected(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected rejection");
        } catch (IllegalArgumentException expected) { }
    }

    private static void expectNoLeak(String name) {
        int now = fdCount();
        if (now != baseline) throw new AssertionError(name + " fd baseline=" + baseline + " now=" + now);
        System.out.println(name + "=PASS fd=" + now);
    }

    private static void check(boolean condition,String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] fakePng() {
        byte[] bytes = new byte[45];
        byte[] signature = {(byte) 137,80,78,71,13,10,26,10};
        System.arraycopy(signature,0,bytes,0,8);
        bytes[11] = 13; bytes[12] = 73; bytes[13] = 72; bytes[14] = 68; bytes[15] = 82;
        bytes[18] = 2; bytes[19] = (byte) 208; bytes[22] = 5; bytes[24] = 8; bytes[25] = 6;
        return bytes;
    }
}
