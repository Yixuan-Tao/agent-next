package agent.phone.connection;

import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import agent.phone.executor.ProtocolPolicy;
import agent.phone.ipc.IAgentShell;
import moe.shizuku.server.IShizukuService;
import moe.shizuku.server.IShizukuServiceConnection;
import rikka.shizuku.Shizuku;
import static agent.phone.connection.ConnectionState.Reason;

final class ShizukuBackend implements ConnectionCoordinator.Backend {
    interface ManagerSource { IShizukuService get(); }
    private final ManagerSource source;
    private final String ownerPackage;
    private final Map<ConnectionCoordinator.Binding, Registration> registrations = new ConcurrentHashMap<>();

    ShizukuBackend(Context context) {
        this(() -> IShizukuService.Stub.asInterface(Shizuku.getBinder()), context.getPackageName());
    }

    ShizukuBackend(ManagerSource source) {
        this(source, UserServiceProtocol.DEFAULT_PACKAGE);
    }

    private ShizukuBackend(ManagerSource source, String ownerPackage) {
        this.source = source;
        if (ownerPackage == null || ownerPackage.trim().isEmpty()) {
            throw new IllegalArgumentException("Owner package is required");
        }
        this.ownerPackage = ownerPackage;
    }
    @Override public ConnectionState inspect() { return ConnectionChecks.inspect(); }
    @Override public void requestPermission(int code) { Shizuku.requestPermission(code); }

    private IShizukuService manager() throws RemoteException {
        IShizukuService manager = source.get();
        if (manager == null) throw new DeadObjectException();
        if (manager.getUid() != 2000) throw new SecurityException("Only the shell Shizuku manager is supported");
        return manager;
    }

    @Override public void bind(ConnectionCoordinator.Binding binding) throws RemoteException {
        IShizukuService manager = manager();
        if (!manager.checkSelfPermission()) throw new SecurityException("Shizuku permission is not granted");
        Registration registration = registrations.computeIfAbsent(binding, Registration::new);
        synchronized (registration) { if (registration.cancelled) return; }
        int result = manager.addUserService(registration, UserServiceProtocol.add(ownerPackage));
        if (result != 0) throw new IllegalStateException("Unexpected addUserService result");
    }

    @Override public void unbind(ConnectionCoordinator.Binding binding, boolean remove) throws RemoteException {
        Registration registration = registrations.computeIfAbsent(binding, Registration::new);
        RemoteService remote = registration.cancel();
        Exception closeFailure = null;
        try { if (remove && remote != null) remote.destroy(); }
        catch (RemoteException | RuntimeException failure) { closeFailure = failure; }
        int result = manager().removeUserService(remove ? null : registration, UserServiceProtocol.remove(remove, ownerPackage));
        // 0 means removed/detached; 1 means the fixed service record was already absent.
        if (result != 0 && result != 1) throw new IllegalStateException("Unexpected removeUserService result");
        if (closeFailure instanceof RemoteException) throw (RemoteException) closeFailure;
        if (closeFailure != null) throw (RuntimeException) closeFailure;
    }

    @Override public void retire(ConnectionCoordinator.Binding binding) {
        // The coordinator calls this only after bind returned and the final remote cleanup succeeded.
        registrations.remove(binding);
    }

    private final class Registration extends IShizukuServiceConnection.Stub {
        private final ConnectionCoordinator.Binding binding;
        private boolean cancelled;
        private RemoteService remote;

        Registration(ConnectionCoordinator.Binding binding) { this.binding = binding; }

        @Override public void connected(IBinder binder) {
            requireManagerCaller();
            synchronized (this) {
                if (cancelled || remote != null) return;
                if (binder == null) throw new IllegalArgumentException("Missing UserService Binder");
                remote = new RemoteService(binder, this);
                // Binding only posts an event; cancellation and publication share this short lock.
                binding.connected(remote);
            }
        }

        @Override public void died() { requireManagerCaller(); disconnected(); }

        synchronized void disconnected() {
            if (cancelled) return;
            cancelled = true;
            binding.disconnected();
        }

        synchronized RemoteService cancel() { cancelled = true; return remote; }
    }

    private static void requireManagerCaller() {
        if (Binder.getCallingUid() != 2000) throw new SecurityException("Untrusted UserService callback caller");
    }

    private final class RemoteService implements ConnectionCoordinator.Service {
        private final IBinder binder;
        private final IBinder.DeathRecipient death;
        private final AtomicBoolean linked = new AtomicBoolean();
        private final AtomicBoolean closing = new AtomicBoolean();
        private boolean destroyRequested;
        private volatile DisplaySession display;

        RemoteService(IBinder binder, Registration registration) {
            this.binder = binder;
            death = registration::disconnected;
        }

        @Override public Capabilities read() throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                if (linked.compareAndSet(false, true)) binder.linkToDeath(death, 0);
                return capabilities(IAgentShell.Stub.asInterface(binder).getCapabilities(ProtocolPolicy.VERSION));
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST, failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED, failure);
            }
        }

        @Override public DisplaySession createDisplay() throws ConnectionCoordinator.Failure {
            return displayCall(null, 0);
        }
        @Override public DisplaySession checkDisplay(DisplaySession expected) throws ConnectionCoordinator.Failure {
            return displayCall(expected, 1);
        }
        @Override public DisplaySession releaseDisplay(DisplaySession expected) throws ConnectionCoordinator.Failure {
            return displayCall(expected, 2);
        }

        @Override public TargetSession bindTarget(DisplaySession expected, String taskId, String[] allowed, String selected)
                throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                Bundle packet = IAgentShell.Stub.asInterface(binder).bindTarget(taskId,expected.token,
                        expected.generation,allowed,selected);
                return TargetPackets.decode(packet,expected,taskId,selected);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST,failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
        }

        @Override public TargetObservation readTarget(TargetSession expected, boolean launch)
                throws ConnectionCoordinator.Failure {
            Bundle packet;
            try {
                requireUsable();
                IAgentShell remote = IAgentShell.Stub.asInterface(binder);
                packet = launch ? remote.launchTarget(expected.token,expected.generation)
                        : remote.observeTarget(expected.token,expected.generation);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST,failure);
            } catch (IllegalStateException failure) {
                throw new ConnectionCoordinator.Failure(Reason.FAILURE,failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
            try {
                return ObservationPackets.decode(packet,expected,0);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
        }

        @Override public TargetTap tapTarget(TargetSession expected,String observationId,int x,int y)
                throws ConnectionCoordinator.Failure {
            Bundle packet;
            try {
                requireUsable();
                packet = IAgentShell.Stub.asInterface(binder).tapTarget(expected.token,expected.generation,
                        observationId,x,y);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST,failure);
            } catch (IllegalStateException failure) {
                throw new ConnectionCoordinator.Failure(Reason.FAILURE,failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
            try {
                return TargetTapPackets.decode(packet,expected.display,observationId);
            } catch (TargetTap.StaleException failure) {
                throw new ConnectionCoordinator.Failure(Reason.STALE_INPUT,failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
        }

        @Override public TargetText setTextTarget(TargetSession expected,String observationId,String ref,String text)
                throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                Bundle packet = IAgentShell.Stub.asInterface(binder).setTextTarget(expected.token,expected.generation,
                        observationId,ref,text);
                return TargetTextPackets.decode(packet,expected.display,observationId,ref);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST,failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
        }

        @Override public TargetSwipe swipeTarget(TargetSession expected,String observationId,int startX,int startY,
                int endX,int endY,int durationMs) throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                Bundle packet = IAgentShell.Stub.asInterface(binder).swipeTarget(expected.token,expected.generation,
                        observationId,startX,startY,endX,endY,durationMs);
                return TargetSwipePackets.decode(packet,expected.display,observationId);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST,failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
        }

        @Override public TargetBack backTarget(TargetSession expected,String observationId)
                throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                Bundle packet = IAgentShell.Stub.asInterface(binder).backTarget(expected.token,expected.generation,
                        observationId);
                return TargetBackPackets.decode(packet,expected.display,observationId);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST,failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
        }

        @Override public void userTap(TargetSession expected,int x,int y) throws ConnectionCoordinator.Failure {
            try { requireUsable(); IAgentShell.Stub.asInterface(binder).userTap(expected.token,expected.generation,x,y); }
            catch (RemoteException failure) { throw new ConnectionCoordinator.Failure(Reason.LOST, failure); }
            catch (RuntimeException failure) { throw new ConnectionCoordinator.Failure(Reason.MALFORMED, failure); }
        }

        @Override public void userSwipe(TargetSession expected,int x1,int y1,int x2,int y2,int durationMs)
                throws ConnectionCoordinator.Failure {
            try { requireUsable(); IAgentShell.Stub.asInterface(binder).userSwipe(expected.token,expected.generation,x1,y1,x2,y2,durationMs); }
            catch (RemoteException failure) { throw new ConnectionCoordinator.Failure(Reason.LOST, failure); }
            catch (RuntimeException failure) { throw new ConnectionCoordinator.Failure(Reason.MALFORMED, failure); }
        }

        @Override public void startPreviewMirror(DisplaySession expected,android.view.Surface surface,int channel)
                throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                Bundle packet = IAgentShell.Stub.asInterface(binder).startPreviewMirror(expected.token,
                        expected.generation,surface,channel);
                if (packet == null || !packet.getBoolean("ok")
                        || packet.getInt("display_id",-1) != expected.displayId) {
                    throw new IllegalArgumentException("Malformed mirror receipt");
                }
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST, failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED, failure);
            }
        }

        @Override public void stopPreviewMirror(DisplaySession expected,int channel) throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                IAgentShell.Stub.asInterface(binder).stopPreviewMirror(expected.token,expected.generation,channel);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST, failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED, failure);
            }
        }

        @Override public int moveTargetToDisplay(TargetSession expected,int displayId) throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                Bundle packet = IAgentShell.Stub.asInterface(binder).moveTargetToDisplay(expected.token,
                        expected.generation,displayId);
                if (packet == null || !packet.getBoolean("ok") || packet.getInt("root_task_id",0) <= 0
                        || packet.getInt("display_id",-1) != displayId) {
                    throw new IllegalArgumentException("Malformed move receipt");
                }
                return packet.getInt("root_task_id",0);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST, failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED, failure);
            }
        }

        @Override public FrameEvidence.Capture readFrame(TargetSession expected) throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                Bundle packet = IAgentShell.Stub.asInterface(binder).captureTarget(expected.token,expected.generation);
                return FramePackets.decode(packet,expected,0);
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST,failure);
            } catch (RuntimeException failure) {
                if (failure.getMessage() != null
                        && failure.getMessage().startsWith("Frame capture rejected:")) {
                    throw new ConnectionCoordinator.Failure(Reason.DENIED, failure);
                }
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED,failure);
            }
        }

        private DisplaySession displayCall(DisplaySession expected, int operation) throws ConnectionCoordinator.Failure {
            try {
                requireUsable();
                IAgentShell remote = IAgentShell.Stub.asInterface(binder);
                Bundle packet = operation == 0 ? remote.createDisplay() : operation == 1
                        ? remote.checkDisplay(expected.token, expected.generation)
                        : remote.releaseDisplay(expected.token, expected.generation);
                DisplaySession result = displaySession(packet);
                if (expected != null && !expected.sameIdentity(result)) {
                    throw new IllegalArgumentException("Display response identity changed");
                }
                display = result;
                return result;
            } catch (RemoteException failure) {
                throw new ConnectionCoordinator.Failure(Reason.LOST, failure);
            } catch (RuntimeException failure) {
                throw new ConnectionCoordinator.Failure(Reason.MALFORMED, failure);
            }
        }

        private void requireUsable() throws RemoteException {
            if (closing.get()) throw new DeadObjectException();
            if (!manager().checkSelfPermission()) throw new SecurityException("Shizuku permission was revoked");
            if (!binder.pingBinder()) throw new DeadObjectException();
            requireDescriptor();
        }

        private void requireDescriptor() throws RemoteException {
            if (!IAgentShell.DESCRIPTOR.equals(binder.getInterfaceDescriptor())) {
                throw new IllegalArgumentException("Unexpected UserService Binder interface");
            }
        }

        @Override public synchronized void destroy() throws RemoteException {
            closing.set(true);
            Exception closeFailure = null;
            if (!destroyRequested && binder.isBinderAlive()) {
                try {
                    requireDescriptor();
                    IAgentShell remote = IAgentShell.Stub.asInterface(binder);
                    DisplaySession previous = display;
                    try {
                        if (previous != null && previous.status != DisplaySession.Status.RELEASED) {
                            DisplaySession released = displaySession(remote.releaseDisplay(previous.token, previous.generation));
                            if (!previous.sameIdentity(released) || released.status != DisplaySession.Status.RELEASED) {
                                throw new IllegalStateException("Display cleanup is unconfirmed");
                            }
                            display = released;
                        }
                    } finally {
                        remote.destroy();
                        destroyRequested = true;
                    }
                } catch (RemoteException | RuntimeException failure) { closeFailure = failure; }
            }
            // Oneway delivery, an absent manager record, and a requested close are not death evidence.
            // Recheck on every cleanup attempt, including unknown/late creates and prior failures.
            long started = System.nanoTime();
            while (binder.isBinderAlive() && System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500)) {
                try { Thread.sleep(25); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Service exit confirmation was interrupted", interrupted);
                }
            }
            if (binder.isBinderAlive()) throw new IllegalStateException("Service exit is not confirmed", closeFailure);
            if (linked.getAndSet(false)) {
                try { binder.unlinkToDeath(death, 0); }
                catch (RuntimeException alreadyUnlinked) { }
            }
        }

    }

    @SuppressWarnings("deprecation")
    private static DisplaySession displaySession(Bundle packet) {
        if (packet == null || !(packet.get("session_token") instanceof String)
                || !(packet.get("generation") instanceof Long) || !(packet.get("unique_id") instanceof String)
                || !(packet.get("status") instanceof String)) {
            throw new IllegalArgumentException("Missing display session fields");
        }
        String[] integers = {"display_id", "owner_uid", "width", "height", "density_dpi", "flags"};
        for (String key : integers) if (!(packet.get(key) instanceof Integer)) {
            throw new IllegalArgumentException("Invalid display metadata field");
        }
        return new DisplaySession((String) packet.get("session_token"), (Long) packet.get("generation"),
                packet.getInt("display_id", -1), packet.getString("unique_id"), packet.getInt("owner_uid", -1),
                packet.getInt("width", -1), packet.getInt("height", -1), packet.getInt("density_dpi", -1),
                packet.getInt("flags", -1), DisplaySession.Status.valueOf(packet.getString("status")));
    }

    private static Capabilities capabilities(Bundle bundle) {
        if (bundle == null || !ProtocolPolicy.SCOPE.equals(bundle.getString(ProtocolPolicy.KEY_SCOPE))
                || !bundle.containsKey(ProtocolPolicy.KEY_SUPPORTED)) {
            throw new IllegalArgumentException("Missing handshake capabilities");
        }
        int protocol = bundle.getInt(ProtocolPolicy.KEY_PROTOCOL, -1);
        int api = bundle.getInt(ProtocolPolicy.KEY_API, -1);
        int serviceUid = bundle.getInt(ProtocolPolicy.KEY_SERVICE_UID, -1);
        int appUid = bundle.getInt(ProtocolPolicy.KEY_APP_UID, -1);
        boolean supported = bundle.getBoolean(ProtocolPolicy.KEY_SUPPORTED);
        boolean previewMirror = bundle.getBoolean(ProtocolPolicy.KEY_PREVIEW_MIRROR, false);
        ProtocolPolicy.validateCapabilities(protocol, api, serviceUid, appUid, Process.myUid(), supported);
        return new Capabilities(protocol, api, serviceUid, appUid, supported, previewMirror);
    }
}
