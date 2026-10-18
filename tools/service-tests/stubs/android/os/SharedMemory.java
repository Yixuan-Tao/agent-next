package android.os;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.nio.ByteBuffer;
public final class SharedMemory implements Parcelable,AutoCloseable {
    private final byte[] bytes;
    public boolean closed;
    public int writeMappingErrno;
    private boolean sealed;
    public static int mappings;
    private SharedMemory(int size) { bytes = new byte[size]; }
    public static SharedMemory create(String name,int size) { return new SharedMemory(size); }
    public ByteBuffer mapReadWrite() throws ErrnoException {
        if (writeMappingErrno != 0) throw new ErrnoException("mapping failed",writeMappingErrno);
        if (closed) throw new ErrnoException("closed",OsConstants.EBADF);
        if (sealed) throw new ErrnoException("not writable",OsConstants.EPERM);
        mappings++; return ByteBuffer.wrap(bytes);
    }
    public ByteBuffer mapReadOnly() throws ErrnoException {
        if (closed) throw new ErrnoException("closed",OsConstants.EBADF);
        mappings++; return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }
    public static void unmap(ByteBuffer mapped) { if (mapped == null) throw new IllegalArgumentException(); mappings--; }
    public boolean setProtect(int protection) { sealed = protection == OsConstants.PROT_READ; return true; }
    public int getSize() { if (closed) throw new IllegalStateException("closed"); return bytes.length; }
    @Override public void close() { closed = true; }
    @Override public void writeToParcel(Parcel parcel,int flags) { parcel.addBytes(32); }
}
