package android.os;
import java.util.concurrent.CopyOnWriteArrayList;
public class Binder implements IBinder {
    private static final ThreadLocal<Integer> CALLER = ThreadLocal.withInitial(() -> 10234);
    private final CopyOnWriteArrayList<DeathRecipient> deaths = new CopyOnWriteArrayList<>();
    private IInterface owner;
    private String descriptor;
    public static int getCallingUid() { return CALLER.get(); }
    public static void setTestCallingUid(int uid) { CALLER.set(uid); }
    public void attachInterface(IInterface value, String name) { owner = value; descriptor = name; }
    @Override public IInterface queryLocalInterface(String name) { return name.equals(descriptor) ? owner : null; }
    @Override public String getInterfaceDescriptor() { return descriptor; }
    @Override public boolean pingBinder() { return true; }
    @Override public boolean isBinderAlive() { return true; }
    @Override public void linkToDeath(DeathRecipient death, int flags) { deaths.add(death); }
    @Override public boolean unlinkToDeath(DeathRecipient death, int flags) { return deaths.remove(death); }
    @Override public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        throw new UnsupportedOperationException("Host adapter test does not emulate Binder transport");
    }
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException { return false; }
}
