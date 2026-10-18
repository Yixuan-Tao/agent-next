import android.os.Build;
import android.os.Parcel;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Public-API shared memory probe. Payload is synthetic and never contains app data. */
public final class FrameMemoryProbe {
    private static final int LENGTH = 4096;
    private static final int ROUNDS = 100;

    public static void main(String[] args) throws Exception {
        System.out.println("probe=SharedMemoryParcel synthetic_bytes=" + LENGTH
                + " sdk=" + Build.VERSION.SDK_INT + " rounds=" + ROUNDS);
        check(Build.VERSION.SDK_INT >= 36, "API 36 or later required");
        runRound(-1); // Load framework paths before counting process descriptors.
        int before = fdCount();
        int min = before;
        int max = before;
        for (int round = 0; round < ROUNDS; round++) {
            int deniedErrno = runRound(round);
            int count = fdCount();
            min = Math.min(min, count);
            max = Math.max(max, count);
            if ((round + 1) % 25 == 0) {
                System.out.println("completed=" + (round + 1) + " fd=" + count
                        + " write_denied_errno=" + deniedErrno);
            }
        }
        int after = fdCount();
        check(Math.abs(after - before) <= 2, "descriptor count drift: " + before + " -> " + after);
        check(max - min <= 2, "descriptor count unstable: " + min + ".." + max);
        System.out.println("PASS rounds=" + ROUNDS + " content_equal=100 read_only=100"
                + " write_mapping_denied=100 retained_bytes_after_close=100"
                + " fd_before=" + before + " fd_after=" + after
                + " fd_min=" + min + " fd_max=" + max);
        System.out.println("BOUNDARY local Parcel FD duplication only; no Binder service, screenshot,"
                + " display, UiAutomation, or UI interaction. Closing handles cannot revoke bytes"
                + " already copied to a receiver byte array.");
    }

    private static int runRound(int round) throws Exception {
        byte[] expected = new byte[LENGTH];
        for (int index = 0; index < LENGTH; index++) {
            expected[index] = (byte) ((index * 31 + round * 17) & 0xff);
        }
        SharedMemory source = null;
        SharedMemory copy = null;
        Parcel parcel = null;
        ByteBuffer writer = null;
        ByteBuffer reader = null;
        ByteBuffer unexpectedWriter = null;
        byte[] received = new byte[LENGTH];
        int deniedErrno = 0;
        try {
            source = SharedMemory.create("synthetic-frame-memory-probe", LENGTH);
            check(source.getSize() == LENGTH, "source size");
            writer = source.mapReadWrite();
            writer.put(expected);
            SharedMemory.unmap(writer);
            writer = null;
            check(source.setProtect(OsConstants.PROT_READ), "setProtect(PROT_READ)");
            parcel = Parcel.obtain();
            source.writeToParcel(parcel, 0);
            check(parcel.hasFileDescriptors(), "Parcel must transport an FD");
            parcel.setDataPosition(0);
            copy = SharedMemory.CREATOR.createFromParcel(parcel);
            check(copy.getSize() == LENGTH, "copy size");
            reader = copy.mapReadOnly();
            check(reader.isReadOnly(), "receiver ByteBuffer must be read-only");
            reader.get(received);
            check(Arrays.equals(expected, received), "receiver content mismatch");
            try {
                unexpectedWriter = copy.mapReadWrite();
                throw new AssertionError("receiver write mapping unexpectedly allowed");
            } catch (ErrnoException expectedDenial) {
                deniedErrno = expectedDenial.errno;
                check(deniedErrno == OsConstants.EACCES || deniedErrno == OsConstants.EPERM,
                        "unexpected denial errno=" + deniedErrno);
            }
        } finally {
            if (unexpectedWriter != null) SharedMemory.unmap(unexpectedWriter);
            if (reader != null) SharedMemory.unmap(reader);
            if (writer != null) SharedMemory.unmap(writer);
            if (copy != null) copy.close();
            if (source != null) source.close();
            if (parcel != null) parcel.recycle();
        }
        check(Arrays.equals(expected, received), "previously copied bytes changed after close");
        return deniedErrno;
    }

    private static int fdCount() {
        String[] descriptors = new File("/proc/self/fd").list();
        check(descriptors != null, "cannot inspect this probe process descriptors");
        return descriptors.length;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
