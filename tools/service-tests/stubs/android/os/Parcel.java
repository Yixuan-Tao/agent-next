package android.os;
public final class Parcel {
    private int bytes;
    void addBytes(int count) { bytes += count; }
    public int dataSize() { return bytes; }
    public void writeStringArray(String[] value) { throw new UnsupportedOperationException(); }
    public String[] createStringArray() { throw new UnsupportedOperationException(); }
    public static Parcel obtain() { return new Parcel(); }
    public void recycle() { }
    public void enforceInterface(String value) { throw new UnsupportedOperationException(); }
    public void writeInterfaceToken(String value) { throw new UnsupportedOperationException(); }
    public void writeString(String value) { throw new UnsupportedOperationException(); }
    public void writeInt(int value) { throw new UnsupportedOperationException(); }
    public void writeLong(long value) { throw new UnsupportedOperationException(); }
    public long readLong() { throw new UnsupportedOperationException(); }
    public String readString() { throw new UnsupportedOperationException(); }
    public int readInt() { throw new UnsupportedOperationException(); }
    public void writeNoException() { throw new UnsupportedOperationException(); }
    public void readException() { throw new UnsupportedOperationException(); }
}
